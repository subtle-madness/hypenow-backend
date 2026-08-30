#!/usr/bin/env bash
# 파라미터 축 하나를 골라 포인트마다 재시드→측정을 반복 — 병목 귀속용 스윕.
# 사용: bench/sweep.sh <K|D|C|N|all>
#   기준점: N=10 M=3 K=12 D=8 C=12 (운영 재현 규모, 2026-08-27 실측 분포)
#   결과: bench/results/sweep-<UTC타임스탬프>.csv (CSV_OUT 지정 시 그 파일에 append)
set -euo pipefail
cd "$(dirname "$0")/.."

AXIS="${1:?사용법: bench/sweep.sh <K|D|C|N|all>}"
BASE_N=10 BASE_M=3 BASE_K=12 BASE_D=8 BASE_C=12
CSV_OUT="${CSV_OUT:-bench/results/sweep-$(date -u +%Y%m%d%H%M%S).csv}"
mkdir -p "$(dirname "$CSV_OUT")"
export CSV_OUT

run_point() { # $1=축 $2=값 $3..=seed.sh 인자
	local axis="$1" value="$2"; shift 2
	echo "--- 시드: $axis=$value ---"
	bench/seed.sh "$@"
	bench/run.sh "$axis=$value"
}

sweep_K() { for k in 12 33 100 300; do run_point K "$k" --n-users $BASE_N --campaigns-per-user $BASE_M --items-per-user $BASE_K --snapshot-days $BASE_D --comments-per-post $BASE_C --bench-items "$k"; done; }
sweep_D() { for d in 8 28 90; do run_point D "$d" --n-users $BASE_N --campaigns-per-user $BASE_M --items-per-user $BASE_K --snapshot-days "$d" --comments-per-post $BASE_C --bench-items $BASE_K; done; }
sweep_C() { for c in 12 50 200; do run_point C "$c" --n-users $BASE_N --campaigns-per-user $BASE_M --items-per-user $BASE_K --snapshot-days $BASE_D --comments-per-post "$c" --bench-items $BASE_K; done; }
sweep_N() { for n in 10 100 1000 5000; do run_point N "$n" --n-users "$n" --campaigns-per-user $BASE_M --items-per-user $BASE_K --snapshot-days $BASE_D --comments-per-post $BASE_C --bench-items $BASE_K; done; }

case "$AXIS" in
	K) sweep_K ;;
	D) sweep_D ;;
	C) sweep_C ;;
	N) sweep_N ;;
	all) sweep_K; sweep_D; sweep_C; sweep_N ;;
	*) echo "알 수 없는 축: $AXIS (K|D|C|N|all)" >&2; exit 1 ;;
esac

echo "결과: $CSV_OUT"
