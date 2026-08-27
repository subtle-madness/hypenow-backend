#!/usr/bin/env bash
# 벤치 목 데이터 시드 래퍼 — 프리셋 또는 개별 파라미터로 두 DB(app·monitoring)를 시드한다.
# 사용:
#   bench/seed.sh prod|x10|x100
#   bench/seed.sh --n-users 100 --campaigns-per-user 3 --items-per-user 12 \
#                 --snapshot-days 8 --comments-per-post 12 --bench-items 33
# 환경변수: PG_CONTAINER(기본 hypenow-bench-postgres)
# 벤치 유저: bench@bench.local / bench-password (u=0, --bench-items개 아이템)
set -euo pipefail
cd "$(dirname "$0")"

PG_CONTAINER="${PG_CONTAINER:-hypenow-bench-postgres}"
# BCryptPasswordEncoder는 $2y$ 검증 가능 — htpasswd -bnBC 10 산출물
BENCH_HASH='$2y$10$6AWCciTxUgJqPMb23pYJsuAJQVs7524w5gv7A9U0oZgTGUh0ijahy'

N_USERS=10 CAMPAIGNS=3 ITEMS=12 DAYS=8 COMMENTS=12 BENCH_ITEMS=12

case "${1:-}" in
	prod) ;;                                             # 기본값 = 운영 재현(2026-08-27 실측 분포)
	x10)  N_USERS=100  BENCH_ITEMS=33 ;;
	x100) N_USERS=1000 BENCH_ITEMS=120 DAYS=28 ;;
	--*) while [[ $# -gt 0 ]]; do
			case "$1" in
				--n-users) N_USERS=$2 ;;
				--campaigns-per-user) CAMPAIGNS=$2 ;;
				--items-per-user) ITEMS=$2 ;;
				--snapshot-days) DAYS=$2 ;;
				--comments-per-post) COMMENTS=$2 ;;
				--bench-items) BENCH_ITEMS=$2 ;;
				*) echo "알 수 없는 옵션: $1" >&2; exit 1 ;;
			esac
			shift 2
		done ;;
	*) echo "사용법: bench/seed.sh <prod|x10|x100|--옵션들>" >&2; exit 1 ;;
esac

VARS=(-v n_users="$N_USERS" -v campaigns_per_user="$CAMPAIGNS" -v items_per_user="$ITEMS"
      -v snapshot_days="$DAYS" -v comments_per_post="$COMMENTS" -v bench_items="$BENCH_ITEMS")

echo "시드: N=$N_USERS M=$CAMPAIGNS K=$ITEMS D=$DAYS C=$COMMENTS bench_K=$BENCH_ITEMS (컨테이너 $PG_CONTAINER)"
docker exec -i "$PG_CONTAINER" psql -q -U crawler -d analysis \
	"${VARS[@]}" -v bench_hash="$BENCH_HASH" -f /dev/stdin < seed_app.sql
docker exec -i "$PG_CONTAINER" psql -q -U monitoring -d monitoring \
	"${VARS[@]}" -f /dev/stdin < seed_monitoring.sql
echo "시드 완료"
