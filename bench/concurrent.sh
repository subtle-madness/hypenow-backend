#!/usr/bin/env bash
# 동시성 벤치 — monitoring-ro 커넥션 풀(maxPoolSize 3) 대기 재현 실험.
# 사용: bench/concurrent.sh <동시성> [총요청수(기본 100)]
#   환경변수: BASE_URL(기본 http://localhost:8091), BENCH_EMAIL, BENCH_PASSWORD
# 1초 넘는 요청은 was의 SlowRequestStageLogFilter가 단계 분해를 로그로 남긴다 —
# 풀 대기라면 monitoring 리포지토리 단계만 부풀고 app DB 단계는 평시 그대로여야 한다.
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8091}"
BENCH_EMAIL="${BENCH_EMAIL:-bench@bench.local}"
BENCH_PASSWORD="${BENCH_PASSWORD:-bench-password}"
CONC="${1:?사용법: bench/concurrent.sh <동시성> [총요청수]}"
TOTAL="${2:-100}"
EP="/v1/monitoring/items"

JAR="$(mktemp)" OUT="$(mktemp)"
trap 'rm -f "$JAR" "$OUT"' EXIT

curl -s -o /dev/null -c "$JAR" "$BASE_URL/v1/stats"
XSRF=$(awk '$6 == "XSRF-TOKEN" {print $7}' "$JAR")
for attempt in 1 2 3; do
	code=$(curl -s -o /dev/null -w '%{http_code}' -b "$JAR" -c "$JAR" \
		-X POST "$BASE_URL/v1/auth/login" \
		-H 'Content-Type: application/json' ${XSRF:+-H "X-XSRF-TOKEN: $XSRF"} \
		-d "{\"email\":\"$BENCH_EMAIL\",\"password\":\"$BENCH_PASSWORD\"}")
	[[ "$code" == 2* ]] && break
	[[ "$code" == 429 ]] && { echo "로그인 레이트리밋 — 65초 대기($attempt/3)" >&2; sleep 65; continue; }
	echo "로그인 실패 (HTTP $code)" >&2; exit 1
done
[[ "$code" == 2* ]] || { echo "로그인 실패 (재시도 소진)" >&2; exit 1; }

# 워밍업(순차 3회) 후 본측정
for _ in 1 2 3; do curl -s -o /dev/null -b "$JAR" --compressed "$BASE_URL$EP"; done
seq "$TOTAL" | xargs -P "$CONC" -I{} \
	curl -s -o /dev/null -b "$JAR" --compressed \
	-w '%{time_total} %{http_code}\n' "$BASE_URL$EP" > "$OUT"

python3 - "$CONC" "$OUT" <<-'PY'
import sys
conc, path = sys.argv[1], sys.argv[2]
times, bad = [], 0
for line in open(path):
    t, c = line.split()
    if c.startswith("2"):
        times.append(float(t) * 1000)
    else:
        bad += 1
times.sort()
q = lambda p: times[min(len(times) - 1, int(len(times) * p))]
print(f"동시성={conc} n={len(times)} p50={q(.5):.0f}ms p95={q(.95):.0f}ms "
      f"p99={q(.99):.0f}ms max={times[-1]:.0f}ms" + (f" 실패={bad}" if bad else ""))
PY
