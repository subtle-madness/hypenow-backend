#!/usr/bin/env bash
# analytics 뷰를 적용하고 트랜잭션 격리로 테스트를 돌린다.
# 사용법: ./test/run.sh              (전체 테스트)
#         ./test/run.sh test/01_x.test.sql   (지정 테스트)
set -euo pipefail
shopt -s nullglob
cd "$(dirname "$0")/.."

PSQL=(docker exec -i crawler-postgres-1 psql -U crawler -d crawler -v ON_ERROR_STOP=1 -q)

# 1) 뷰 적용 (파일명 순, 멱등). 아직 뷰가 없으면 건너뛴다.
for v in views/*.sql; do
  echo "apply $v"
  "${PSQL[@]}" < "$v"
done

# 2) 테스트 실행. 각 테스트는 BEGIN; 더미 seed; assert; ROLLBACK; 으로 격리.
tests=("$@")
if [ ${#tests[@]} -eq 0 ]; then tests=(test/*.test.sql); fi
# nullglob 하에서 매칭되는 테스트가 없으면 tests가 비어 "${tests[@]}"가 set -u에서 크래시.
# 확장 전에 개수를 가드한다.
if [ ${#tests[@]} -eq 0 ]; then echo "no tests found"; exit 1; fi
for t in "${tests[@]}"; do
  echo "== $t =="
  { echo 'BEGIN;'; cat seed/dummy.sql; cat "$t"; echo 'ROLLBACK;'; } | "${PSQL[@]}"
  echo "PASS: $t"
done
echo "ALL GREEN"
