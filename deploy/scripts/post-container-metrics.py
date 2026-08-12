#!/usr/bin/env python3
# 컨테이너 상태·디스크 사용률을 OCI 커스텀 메트릭(hypenow_custom)으로 게시 — 서버 크론 1분 주기.
#   * * * * * /home/ubuntu/.venv-oci-metrics/bin/python /home/ubuntu/deploy/scripts/post-container-metrics.py
# OCI 인증은 인스턴스 프린시펄(dynamic group hypenow-instances + policy hypenow-custom-metrics) —
# OCI API 키는 서버에 두지 않는다. 다만 GCS 버킷 크기 조회는 SA 키 파일(GCS_KEY, compose가 쓰는
# 것과 같은 파일)을 읽는다 — 2026-08-12 이미지 스토리지 이전 이후.
# 알람은 OCI 콘솔/CLI에서 이 네임스페이스를 구독(hypenow-alerts 토픽).
# 의존성: python3 -m venv ~/.venv-oci-metrics && ~/.venv-oci-metrics/bin/pip install oci google-auth requests
#   (google-auth·requests는 버킷 크기 게시가 GCS로 옮겨간 2026-08-12부터 필요)
import datetime
import shutil
import subprocess
import sys

import oci

# compose 서비스 고정 목록 — 실행 중인 것만 열거하면 "사라진 컨테이너"가 메트릭 스트림에서
# 빠져 알람이 못 잡는다. 목록에 있는데 컨테이너를 못 찾으면 0(다운)으로 게시된다.
# redis·grafana·ons-relay는 08-05 추가(메모리 격리 후속 — mem_limit OOM 재기동 실패가 조용히
# 묻히던 사각 해소). 특히 ons-relay는 디스코드 알람 전체의 단일 경로라 전용 알람
# hypenow-ons-relay-down이 치명 토픽(이메일 백업)으로 따로 나간다 — README §9.
# prometheus·loki·alloy는 08-10 추가(성능 측정 스택 — README §15). 관측 스택은 oom_score_adj 500이라
# 메모리 압박에서 가장 먼저 죽도록 설계된 쪽인데, 알람 사각이면 죽은 줄 모른 채 지표·로그만 조용히
# 비어 "측정하고 있다"는 착각이 남는다 — 가장 잘 죽는 컨테이너부터 감시 대상이어야 한다.
SERVICES = ["postgres", "postgres-raw", "analytics", "crawler", "was", "caddy", "monitoring",
            "redis", "grafana", "ons-relay", "prometheus", "loki", "alloy"]
# 운영 compose 프로젝트명(디렉토리 ~/deploy 기반). test-* 서비스는 같은 프로젝트지만
# 서비스 라벨이 달라(test-was 등) 아래 필터에 걸리지 않는다.
PROJECT = "deploy"
NAMESPACE = "hypenow_custom"
COMPARTMENT = "ocid1.tenancy.oc1..aaaaaaaat36ksxqom5nzid6jzx2tglneiyganxbjk7t5pgmlvgpc44eozllq"


def container_ids(service: str) -> list[str]:
	"""compose 라벨로 서비스의 컨테이너를 전부 찾는다(정지분 포함). 이름(deploy-<svc>-1)을 쓰면
	안 되는 이유: 롤링 재기동(rollout.sh)이 `--scale <svc>=2`로 다음 빈 인덱스에 신 컨테이너를
	띄우고 구 1번을 제거해서, 첫 롤링 이후로는 -1이 영영 존재하지 않는다 — 07-30 롤링 도입 직후
	was가 상시 다운으로 오탐해 hypenow-container-down이 16시간 넘게 울렸다."""
	result = subprocess.run(
		["docker", "ps", "-aq",
		 "--filter", f"label=com.docker.compose.project={PROJECT}",
		 "--filter", f"label=com.docker.compose.service={service}"],
		capture_output=True, text=True, timeout=10)
	return result.stdout.split() if result.returncode == 0 else []


def container_up(service: str) -> int:
	"""1=정상(running이고, 헬스체크가 있으면 healthy/starting). starting을 살아있음으로 치는 건
	배포 재기동(analytics start_period 90s) 동안의 오탐 방지 — 기동 실패는 곧 unhealthy/exit로 떨어진다.
	롤링 중 복제 2개가 공존하므로 하나라도 살아있으면 1(서비스는 계속 서빙 중이다)."""
	try:
		ids = container_ids(service)
		if not ids:
			return 0
		# returncode는 보지 않는다 — ps~inspect 사이에 한 컨테이너가 사라지면 0이 아니지만
		# 나머지 줄은 유효하다. 판정은 "살아있는 줄이 하나라도 있는가"로 충분하다.
		result = subprocess.run(
			["docker", "inspect", "--format",
			 "{{.State.Running}}|{{if .State.Health}}{{.State.Health.Status}}{{end}}", *ids],
			capture_output=True, text=True, timeout=10)
		for line in result.stdout.splitlines():
			running, _, health = line.strip().partition("|")
			if running == "true" and health in ("", "healthy", "starting"):
				return 1
		return 0
	except Exception:
		return 0


def disk_used_percent(path: str = "/") -> float:
	total, used, _ = shutil.disk_usage(path)
	return round(used / total * 100, 1)


now = datetime.datetime.now(datetime.timezone.utc)


def metric(name: str, dimensions: dict, value: float) -> oci.monitoring.models.MetricDataDetails:
	return oci.monitoring.models.MetricDataDetails(
		namespace=NAMESPACE, compartment_id=COMPARTMENT, name=name, dimensions=dimensions,
		datapoints=[oci.monitoring.models.Datapoint(timestamp=now, value=float(value))])


signer = oci.auth.signers.InstancePrincipalsSecurityTokenSigner()

data = [metric("container_up", {"containerName": s}, container_up(s)) for s in SERVICES]
data.append(metric("disk_used_percent", {"host": "hypenow-api"}, disk_used_percent()))

# OCI 버킷 병행 게시(컷오버 전 관측 공백 방지) — GCS 전환 완료 후 이 블록만 제거한다.
# bucket_used_gb는 dimension으로 스트림이 갈려 알람 정의 수정 불요. 다만 두 스트림의
# bucketName이 같은 "hypenow-images"라 같은 분(minute==0이면서 %5==0)에 둘 다 게시되면
# 같은 dimension으로 datapoint가 2개 들어간다 — OCI 쪽에 provider="oci"를 더해 스트림을 가른다.
OCI_BUCKETS = ["hypenow-images"]
OS_NAMESPACE = "nr4nxrxoojw8"
if now.minute % 5 == 0:
	try:
		os_client = oci.object_storage.ObjectStorageClient({"region": signer.region}, signer=signer)
		for bucket in OCI_BUCKETS:
			size = os_client.get_bucket(OS_NAMESPACE, bucket, fields=["approximateSize"]).data.approximate_size or 0
			data.append(metric("bucket_used_gb", {"bucketName": bucket, "provider": "oci"}, round(size / 2**30, 3)))
	except Exception as e:
		print(f"OCI 버킷 크기 수집 실패: {e}", file=sys.stderr)

# 버킷 용량(GCS, 2026-08-12 이전) — 크기 합산은 전체 목록 페이징이라 정시(hour)에만.
GCS_BUCKETS = ["hypenow-images"]
GCS_KEY = "/home/ubuntu/deploy/secrets/gcs-image-archiver.json"
if now.minute == 0:
	try:
		from google.auth import load_credentials_from_file
		from google.auth.transport.requests import AuthorizedSession
		# 키 파일은 SA 키·gcloud ADC(authorized_user) 둘 다 허용 — devstorage.* 스코프는
		# authorized_user 리프레시에서 invalid_scope가 난다(08-12 실측). cloud-platform은 양쪽 유효.
		creds, _ = load_credentials_from_file(
			GCS_KEY, scopes=["https://www.googleapis.com/auth/cloud-platform"])
		sess = AuthorizedSession(creds)
		for bucket in GCS_BUCKETS:
			total, page = 0, None
			while True:
				params = {"fields": "items(size),nextPageToken", "maxResults": 1000}
				if page:
					params["pageToken"] = page
				# 상태 검사 필수 — requests는 403/404/5xx에 raise하지 않아서, 빠뜨리면
				# 빈 items로 0GB(또는 부분합)가 진짜 값처럼 게시돼 알람이 조용히 죽는다.
				r = sess.get(f"https://storage.googleapis.com/storage/v1/b/{bucket}/o",
					params=params, timeout=30)
				r.raise_for_status()
				body = r.json()
				total += sum(int(o["size"]) for o in body.get("items", []))
				page = body.get("nextPageToken")
				if not page:
					break
			data.append(metric("bucket_used_gb", {"bucketName": bucket}, round(total / 2**30, 3)))
	except Exception as e:  # GCS 실패는 버킷 메트릭 결손으로 한정 — 컨테이너 메트릭 게시는 계속
		print(f"GCS 버킷 크기 수집 실패: {e}", file=sys.stderr)

client = oci.monitoring.MonitoringClient(
	{"region": signer.region}, signer=signer,
	service_endpoint=f"https://telemetry-ingestion.{signer.region}.oraclecloud.com")
response = client.post_metric_data(oci.monitoring.models.PostMetricDataDetails(metric_data=data))
if response.data.failed_metrics_count:
	raise SystemExit(f"게시 실패 {response.data.failed_metrics_count}건: {response.data.failed_metrics}")
