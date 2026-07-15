#!/usr/bin/env bash
# content-ranking 프론트가 요구하는 필드별로 analysis DB 미러의 채움 정도를 보고한다.
# 미러 골격(contents·accounts·content_metric_snapshots)이 비어 있으면 실패.
# 사용법: ./check/coverage.sh   (crawler-postgres-1 필요)
set -euo pipefail
cd "$(dirname "$0")"

docker exec -i crawler-postgres-1 psql -U crawler -d analysis -v ON_ERROR_STOP=1 < coverage.sql
echo "COVERAGE OK (가드 통과 — 표의 '없음' 구간은 미구현 상태 보고용)"
