#!/usr/bin/env bash
# 캠페인 관리 화면 엔드포인트 벤치 측정 — 로그인 1회 후 GET 반복, 분위수 출력.
# 사용: bench/run.sh [라벨]
#   환경변수: BASE_URL(기본 http://localhost:8091), BENCH_EMAIL, BENCH_PASSWORD,
#             N_REQUESTS(기본 50), N_WARMUP(기본 5), CSV_OUT(지정 시 CSV 한 줄씩 append)
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8091}"
BENCH_EMAIL="${BENCH_EMAIL:-bench@bench.local}"
BENCH_PASSWORD="${BENCH_PASSWORD:-bench-password}"
N_REQUESTS="${N_REQUESTS:-50}"
N_WARMUP="${N_WARMUP:-5}"
LABEL="${1:-adhoc}"
ENDPOINTS=("/v1/monitoring/items" "/v1/monitoring/campaigns")

JAR="$(mktemp)"
trap 'rm -f "$JAR"' EXIT

# 로그인 — /v1/auth/login(레거시 /api/auth는 잠김) + CSRF 쿠키→헤더 동봉.
# 레이트리밋(10회/분)이 있어 로그인은 1회만 하고 세션 쿠키를 재사용한다.
curl -s -o /dev/null -c "$JAR" "$BASE_URL/v1/stats"   # XSRF-TOKEN 쿠키 수령용 공개 GET
XSRF=$(awk '$6 == "XSRF-TOKEN" {print $7}' "$JAR")
for attempt in 1 2 3; do
	code=$(curl -s -o /dev/null -w '%{http_code}' -b "$JAR" -c "$JAR" \
		-X POST "$BASE_URL/v1/auth/login" \
		-H 'Content-Type: application/json' ${XSRF:+-H "X-XSRF-TOKEN: $XSRF"} \
		-d "{\"email\":\"$BENCH_EMAIL\",\"password\":\"$BENCH_PASSWORD\"}")
	[[ "$code" == 2* ]] && break
	if [[ "$code" == 429 ]]; then
		echo "로그인 레이트리밋(10회/분) — 65초 대기 후 재시도($attempt/3)" >&2
		sleep 65
	else
		echo "로그인 실패 (HTTP $code) — 벤치 유저 시드가 됐는지 확인" >&2
		exit 1
	fi
done
if [[ "$code" != 2* ]]; then
	echo "로그인 실패 (HTTP $code, 재시도 소진)" >&2
	exit 1
fi

for ep in "${ENDPOINTS[@]}"; do
	# 워밍업(측정 제외) — JIT·커넥션 풀·플랜 캐시 안정화
	for _ in $(seq 1 "$N_WARMUP"); do
		curl -s -o /dev/null -b "$JAR" --compressed "$BASE_URL$ep"
	done
	# 본측정 — 샘플은 임시 파일로 (heredoc이 stdin을 점유하므로 파이프 불가)
	SAMPLES="$(mktemp)"
	for _ in $(seq 1 "$N_REQUESTS"); do
		curl -s -o /dev/null -b "$JAR" --compressed \
			-w '%{time_total} %{size_download} %{http_code}\n' "$BASE_URL$ep"
	done > "$SAMPLES"
	python3 - "$LABEL" "$ep" "$SAMPLES" <<-'PY'
	import sys, statistics
	label, ep, samples_path = sys.argv[1], sys.argv[2], sys.argv[3]
	times, sizes, bad = [], [], 0
	for line in open(samples_path):
	    t, s, c = line.split()
	    if not c.startswith("2"):
	        bad += 1
	        continue
	    times.append(float(t) * 1000)
	    sizes.append(int(s))
	if not times:
	    print(f"{label} {ep}: 전 요청 실패({bad}건)")
	    sys.exit(1)
	times.sort()
	q = lambda p: times[min(len(times) - 1, int(len(times) * p))]
	row = (f"{label} {ep} n={len(times)} p50={q(.5):.0f}ms p95={q(.95):.0f}ms "
	       f"p99={q(.99):.0f}ms max={times[-1]:.0f}ms body={statistics.mean(sizes)/1024:.0f}KB"
	       + (f" 실패={bad}" if bad else ""))
	print(row)
	import os
	csv = os.environ.get("CSV_OUT")
	if csv:
	    import csv as csvmod, pathlib
	    new = not pathlib.Path(csv).exists()
	    with open(csv, "a", newline="") as f:
	        w = csvmod.writer(f)
	        if new:
	            w.writerow(["label", "endpoint", "n", "p50_ms", "p95_ms", "p99_ms",
	                        "max_ms", "mean_body_bytes", "failures"])
	        w.writerow([label, ep, len(times), round(q(.5)), round(q(.95)),
	                    round(q(.99)), round(times[-1]), round(statistics.mean(sizes)), bad])
	PY
	rm -f "$SAMPLES"
done
