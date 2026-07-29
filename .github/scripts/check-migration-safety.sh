#!/usr/bin/env bash
# 무중단(롤링) 배포 가드 — 신규·변경 Flyway 마이그레이션에서 구버전 코드를 즉사시키는
# 파괴적 DDL을 차단한다(expand-contract 규율 — deploy/README.md §5-1).
# was 롤링(트랙 X) 중 신구 코드가 같은 DB를 몇십 초 공존해서 보므로, DROP·RENAME·타입 변경·
# SET NOT NULL은 코드 참조가 끊긴 "다음 릴리스"의 contract 마이그레이션으로 분리해야 한다.
# 의도된 contract 단계는 파일 안에 `-- allow-destructive: <사유>` 주석으로 통과시킨다.
#
# 사용법: check-migration-safety.sh <base-ref>      # git diff 기반 (CI)
#         check-migration-safety.sh --scan <파일…>   # 파일 직접 검사 (셀프테스트용)
set -euo pipefail

# 파괴적 패턴 — 구버전이 참조 중인 객체를 없애거나 바꾸는 DDL만. 추가(ADD/CREATE)는 자유.
DESTRUCTIVE='drop[[:space:]]+table|drop[[:space:]]+column|rename[[:space:]]+(to|column)|alter[[:space:]]+column[[:space:]]+[^[:space:]]+[[:space:]]+(set[[:space:]]+data[[:space:]]+)?type|set[[:space:]]+not[[:space:]]+null'

scan_file() { # 반환 0=통과 1=위반
  local f="$1"
  if grep -qiE '^[[:space:]]*--[[:space:]]*allow-destructive:' "$f"; then
    echo "SKIP $f — allow-destructive 승인 주석"
    return 0
  fi
  # 한 줄 주석(--) 제거 후 검사 — 마이그레이션 컨벤션상 블록 주석은 안 쓴다
  local hits
  hits="$(sed 's/--.*$//' "$f" | grep -niE "$DESTRUCTIVE" || true)"
  if [ -n "$hits" ]; then
    echo "::error file=$f::파괴적 마이그레이션 — 롤링 중 구버전 즉사 위험. expand-contract로 릴리스를 분리하거나, 의도된 contract 단계면 '-- allow-destructive: <사유>' 주석을 추가하세요 (deploy/README.md §5-1)"
    printf '%s\n' "$hits" | sed "s|^|  $f:|"
    return 1
  fi
  echo "OK   $f"
  return 0
}

fail=0
if [ "${1:-}" = "--scan" ]; then
  shift
  for f in "$@"; do scan_file "$f" || fail=1; done
else
  BASE="${1:?사용법: check-migration-safety.sh <base-ref> | --scan <파일…>}"
  files="$(git diff --name-only --diff-filter=AM "$BASE...HEAD" -- \
    'crawler/src/main/resources/db/migration/*.sql' \
    'was/src/main/resources/db/migration/*.sql' \
    'analytics/src/main/resources/db/migration/analysis/*.sql')"
  if [ -z "$files" ]; then
    echo "신규·변경 마이그레이션 없음 — 통과"
    exit 0
  fi
  for f in $files; do
    [ -f "$f" ] || continue
    scan_file "$f" || fail=1
  done
fi
exit $fail
