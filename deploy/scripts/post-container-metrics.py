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
# 빠져 알람이 못 잡는다. 목록에 있는데 inspect가 실패하면 0(다운)으로 게시된다.
SERVICES = ["postgres", "postgres-raw", "analytics", "crawler", "was", "caddy"]
NAMESPACE = "hypenow_custom"
COMPARTMENT = "ocid1.tenancy.oc1..aaaaaaaat36ksxqom5nzid6jzx2tglneiyganxbjk7t5pgmlvgpc44eozllq"

# 오브젝트 스토리지 — OCI가 StoredBytes를 자동 게시하지 않아(2026-07 실측 7일 무데이터)
# 여기서 approximateSize를 직접 게시한다. 정책에 read buckets(해당 버킷 한정) 필요.
BUCKETS = ["hypenow-images"]
OS_NAMESPACE = "nr4nxrxoojw8"


def container_up(service: str) -> int:
	"""1=정상(running이고, 헬스체크가 있으면 healthy/starting). starting을 살아있음으로 치는 건
	배포 재기동(analytics start_period 90s) 동안의 오탐 방지 — 기동 실패는 곧 unhealthy/exit로 떨어진다."""
	name = f"deploy-{service}-1"
	try:
		result = subprocess.run(
			["docker", "inspect", "--format",
			 "{{.State.Running}}|{{if .State.Health}}{{.State.Health.Status}}{{end}}", name],
			capture_output=True, text=True, timeout=10)
		if result.returncode != 0:
			return 0
		running, health = result.stdout.strip().split("|")
		return 1 if running == "true" and health in ("", "healthy", "starting") else 0
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
