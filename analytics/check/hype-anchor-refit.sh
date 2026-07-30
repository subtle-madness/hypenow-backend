#!/usr/bin/env bash
# hype_score 앵커 재적합 후보값을 산출한다(읽기 전용).
# 사용법: ./check/hype-anchor-refit.sh   (실데이터 postgres 컨테이너 필요 — 이름이 다르면 PG_CONTAINER로 지정)
# 대상은 crawler DB(분석 뷰가 사는 곳) — coverage.sh(analysis DB)와 대상이 다르다.
set -euo pipefail
cd "$(dirname "$0")"

# 컨테이너 이름은 compose 디렉토리명 기반이라 머신마다 다르다 — PG_CONTAINER로 오버라이드
docker exec -i "${PG_CONTAINER:-crawler-postgres-1}" psql -U crawler -d crawler -v ON_ERROR_STOP=1 < hype-anchor-refit.sql
echo "REFIT OK (산출값 반영 절차는 hype-anchor-refit.sql 헤더 주석 참조)"
