#!/usr/bin/env python3
# 컨테이너 상태·디스크 사용률을 OCI 커스텀 메트릭(hypenow_custom)으로 게시 — 서버 크론 1분 주기.
#   * * * * * /home/ubuntu/.venv-oci-metrics/bin/python /home/ubuntu/deploy/scripts/post-container-metrics.py
# 인증은 인스턴스 프린시펄(dynamic group hypenow-instances + policy hypenow-custom-metrics) —
# 서버에 키를 두지 않는다. 알람은 OCI 콘솔/CLI에서 이 네임스페이스를 구독(hypenow-alerts 토픽).
# 의존성: python3 -m venv ~/.venv-oci-metrics && ~/.venv-oci-metrics/bin/pip install oci
import datetime
import shutil
import subprocess

import oci

# compose 서비스 고정 목록 — 실행 중인 것만 열거하면 "사라진 컨테이너"가 메트릭 스트림에서
# 빠져 알람이 못 잡는다. 목록에 있는데 컨테이너를 못 찾으면 0(다운)으로 게시된다.
# redis·grafana·ons-relay는 08-05 추가(메모리 격리 후속 — mem_limit OOM 재기동 실패가 조용히
# 묻히던 사각 해소). 특히 ons-relay는 디스코드 알람 전체의 단일 경로라 전용 알람
# hypenow-ons-relay-down이 치명 토픽(이메일 백업)으로 따로 나간다 — README §9.
SERVICES = ["postgres", "postgres-raw", "analytics", "crawler", "was", "caddy", "monitoring",
            "redis", "grafana", "ons-relay"]
# 운영 compose 프로젝트명(디렉토리 ~/deploy 기반). test-* 서비스는 같은 프로젝트지만
# 서비스 라벨이 달라(test-was 등) 아래 필터에 걸리지 않는다.
PROJECT = "deploy"
NAMESPACE = "hypenow_custom"
COMPARTMENT = "ocid1.tenancy.oc1..aaaaaaaat36ksxqom5nzid6jzx2tglneiyganxbjk7t5pgmlvgpc44eozllq"

# 오브젝트 스토리지 — OCI가 StoredBytes를 자동 게시하지 않아(2026-07 실측 7일 무데이터)
# 여기서 approximateSize를 직접 게시한다. 정책에 read buckets(해당 버킷 한정) 필요.
BUCKETS = ["hypenow-images"]
OS_NAMESPACE = "nr4nxrxoojw8"


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

# 버킷 용량은 5분 결에만 조회 — 성장 속도 대비 1분 해상도가 불필요하고 GetBucket API 호출 절약
if now.minute % 5 == 0:
	os_client = oci.object_storage.ObjectStorageClient({"region": signer.region}, signer=signer)
	for bucket in BUCKETS:
		size = os_client.get_bucket(OS_NAMESPACE, bucket, fields=["approximateSize"]).data.approximate_size or 0
		data.append(metric("bucket_used_gb", {"bucketName": bucket}, round(size / 2**30, 3)))

client = oci.monitoring.MonitoringClient(
	{"region": signer.region}, signer=signer,
	service_endpoint=f"https://telemetry-ingestion.{signer.region}.oraclecloud.com")
response = client.post_metric_data(oci.monitoring.models.PostMetricDataDetails(metric_data=data))
if response.data.failed_metrics_count:
	raise SystemExit(f"게시 실패 {response.data.failed_metrics_count}건: {response.data.failed_metrics}")
