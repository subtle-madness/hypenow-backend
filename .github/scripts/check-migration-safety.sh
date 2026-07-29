#!/usr/bin/env bash
# 무중단(롤링) 배포 가드 — 신규·변경 Flyway 마이그레이션에서 구버전 코드를 즉사시키는
# 파괴적 DDL을 차단한다(expand-contract 규율 — deploy/README.md §5-1).
# was 롤링(트랙 X) 중 신구 코드가 같은 DB를 몇십 초 공존해서 보므로, DROP·RENAME·타입 변경·
# SET NOT NULL은 코드 참조가 끊긴 "다음 릴리스"의 contract 마이그레이션으로 분리해야 한다.
# 의도된 contract 단계는 파일 안에 `-- allow-destructive: <사유>` 주석으로 통과시킨다.
#
# v2 (07-29): DROP COLUMN ↔ 보정 UPDATE 짝 검사 — 컬럼 이행(add→전환→drop)에서 롤링 창
# 유실분의 최종 보정을 contract 시점에 기계로 강제한다. DROP COLUMN이 있는 파일은 같은
# 파일에 그 컬럼을 참조하는 UPDATE(멱등 보정 백필)가 있거나, 보정이 불필요한 이유를
# `-- no-backfill: <사유>` 주석으로 명시해야 한다. allow-destructive와는 독립으로 검사한다
# (contract 파일이 정확히 이 검사의 대상이므로).
#
# 대상은 analysis DB 마이그레이션만(was app 스키마 + analytics) — 롤링 중 구 was가 보는 DB.
# crawler(raw)는 대상 외: 재기동 배포(공존 없음)인 데다 was는 raw 접근 금지(시스템 경계)라
# 이 가드의 근거가 성립하지 않고, crawler 트랙은 팀원 담당이다(리뷰 I4).
#
# 사용법: check-migration-safety.sh <base-ref>      # git diff 기반 (CI)
#         check-migration-safety.sh --scan <파일…>   # 파일 직접 검사 (셀프테스트용)
set -euo pipefail

# 파괴적 패턴 — 구버전이 참조 중인 객체를 없애거나 바꾸는 DDL만. 추가(ADD/CREATE)는 자유.
# 한계(리뷰에서 실측 — 리뷰어 몫으로 README §5-1에 문서화): DEFAULT 없는 ADD COLUMN NOT NULL,
# DROP VIEW/INDEX/CONSTRAINT, ADD CONSTRAINT UNIQUE, TRUNCATE, 데이터 형태 변경은 못 잡는다.
DESTRUCTIVE='drop[[:space:]]+table|drop[[:space:]]+column|rename[[:space:]]+(to|column)|alter[[:space:]]+column[[:space:]]+[^[:space:]]+[[:space:]]+(set[[:space:]]+data[[:space:]]+)?type|set[[:space:]]+not[[:space:]]+null'

scan_file() { # 반환 0=통과 1=위반
  local f="$1" rc=0
  # 한 줄 주석(--) 제거 후 줄을 이어붙여 검사 — 여러 줄에 걸친 DDL도 잡는다.
  # 마이그레이션 컨벤션상 블록 주석은 안 쓴다.
  local joined
  joined="$(sed 's/--.*$//' "$f" | tr '\n' ' ')"

  # ① DROP COLUMN ↔ 보정 UPDATE 짝 검사 — allow-destructive 여부와 무관
  if ! grep -qiE '^[[:space:]]*--[[:space:]]*no-backfill:' "$f"; then
    local cols col pat
    cols="$(printf '%s' "$joined" | grep -oiE 'drop[[:space:]]+column[[:space:]]+(if[[:space:]]+exists[[:space:]]+)?"?[a-z_][a-z0-9_]*"?' | awk '{print $NF}' | tr -d '"' | sort -u || true)"
    for col in $cols; do
      pat="update[^;]*[^a-z0-9_]${col}([^a-z0-9_]|\$)"
      if ! printf '%s ' "$joined" | grep -qiE "$pat"; then
        echo "::error file=$f::DROP COLUMN ${col} — 같은 파일에 ${col}을 참조하는 보정 UPDATE(롤링 창 유실분 최종 백필, 멱등)가 없습니다. 보정을 동봉하거나, 불필요하면 '-- no-backfill: <사유>' 주석을 추가하세요 (deploy/README.md §5-1)"
        rc=1
      fi
    done
  fi

  # ② 파괴적 패턴 검사 — 의도된 contract 단계는 allow-destructive 주석으로 스킵
  if grep -qiE '^[[:space:]]*--[[:space:]]*allow-destructive:' "$f"; then
    echo "SKIP(파괴 패턴) $f — allow-destructive 승인 주석"
  else
    local hits
    hits="$(printf '%s' "$joined" | grep -oiE "$DESTRUCTIVE" | sort -u || true)"
    if [ -n "$hits" ]; then
      echo "::error file=$f::파괴적 마이그레이션 — 롤링 중 구버전 즉사 위험. expand-contract로 릴리스를 분리하거나, 의도된 contract 단계면 '-- allow-destructive: <사유>' 주석을 추가하세요 (deploy/README.md §5-1)"
      printf '%s\n' "$hits" | sed "s|^|  $f: 매치 → |"
      rc=1
    fi
  fi

  [ "$rc" -eq 0 ] && echo "OK   $f"
  return $rc
}

fail=0
if [ "${1:-}" = "--scan" ]; then
  shift
  for f in "$@"; do scan_file "$f" || fail=1; done
else
  BASE="${1:?사용법: check-migration-safety.sh <base-ref> | --scan <파일…>}"
  while IFS= read -r f; do
    [ -n "$f" ] && [ -f "$f" ] || continue
    scan_file "$f" || fail=1
  done < <(git diff --name-only --diff-filter=AM "$BASE...HEAD" -- \
    'was/src/main/resources/db/migration/*.sql' \
    'analytics/src/main/resources/db/migration/analysis/*.sql')
  echo "검사 완료 (기준: $BASE...HEAD)"
fi
exit $fail
