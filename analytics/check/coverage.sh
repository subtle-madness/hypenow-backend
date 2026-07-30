#!/usr/bin/env bash
# content-ranking 프론트가 요구하는 필드별로 analysis DB 미러의 채움 정도를 보고한다.
# 미러 골격(contents·accounts, metric_captured_at 포함)이 비어 있으면 실패.
# 사용법: ./check/coverage.sh   (실데이터 postgres 컨테이너 필요 — 이름이 다르면 PG_CONTAINER로 지정)
set -euo pipefail
cd "$(dirname "$0")"

# 컨테이너 이름은 compose 디렉토리명 기반이라 머신마다 다르다 — PG_CONTAINER로 오버라이드
docker exec -i "${PG_CONTAINER:-crawler-postgres-1}" psql -U crawler -d analysis -v ON_ERROR_STOP=1 < coverage.sql
echo "COVERAGE OK (가드 통과 — 표의 '없음' 구간은 미구현 상태 보고용)"
