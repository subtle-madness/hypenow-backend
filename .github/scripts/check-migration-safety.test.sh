#!/usr/bin/env bash
# check-migration-safety.sh --scan 모드 셀프테스트 — CI 가드 잡이 본검사 전에 실행한다
# (cd-test의 rewrite-views-dev-schema.test.sh와 같은 관용구).
set -euo pipefail
SCRIPT="$(cd "$(dirname "$0")" && pwd)/check-migration-safety.sh"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

pass=0; total=0
expect() { # expect <기대코드> <설명> <파일>
  local want="$1" desc="$2" f="$3" got=0
  total=$((total+1))
  "$SCRIPT" --scan "$f" >/dev/null 2>&1 || got=$?
  if [ "$got" -eq "$want" ]; then
    pass=$((pass+1)); echo "ok   $desc"
  else
    echo "FAIL $desc — 기대 $want, 실제 $got"
  fi
}

cat > "$TMP/safe.sql" <<'SQL'
CREATE TABLE app.example (id bigint PRIMARY KEY);
ALTER TABLE app.example ADD COLUMN note text;
CREATE INDEX IF NOT EXISTS idx_example_note ON app.example (note);
SQL
expect 0 "추가만 있는 마이그레이션 통과" "$TMP/safe.sql"

cat > "$TMP/drop-column.sql" <<'SQL'
ALTER TABLE app.example DROP COLUMN note;
SQL
expect 1 "DROP COLUMN 차단" "$TMP/drop-column.sql"

cat > "$TMP/drop-table.sql" <<'SQL'
DROP TABLE app.example;
SQL
expect 1 "DROP TABLE 차단" "$TMP/drop-table.sql"

cat > "$TMP/rename.sql" <<'SQL'
ALTER TABLE app.example RENAME COLUMN note TO memo;
SQL
expect 1 "RENAME COLUMN 차단" "$TMP/rename.sql"

cat > "$TMP/rename-table.sql" <<'SQL'
ALTER TABLE app.example RENAME TO example_v2;
SQL
expect 1 "RENAME TO(테이블) 차단" "$TMP/rename-table.sql"

cat > "$TMP/type-change.sql" <<'SQL'
ALTER TABLE app.example ALTER COLUMN note TYPE varchar(10);
SQL
expect 1 "타입 변경 차단" "$TMP/type-change.sql"

cat > "$TMP/set-not-null.sql" <<'SQL'
ALTER TABLE app.example ALTER COLUMN note SET NOT NULL;
SQL
expect 1 "SET NOT NULL 차단" "$TMP/set-not-null.sql"

cat > "$TMP/comment-only.sql" <<'SQL'
-- 이 주석은 DROP TABLE 언급일 뿐 실행문이 아니다
ALTER TABLE app.example ADD COLUMN extra text;
SQL
expect 0 "주석 속 패턴은 무시" "$TMP/comment-only.sql"

cat > "$TMP/allowed.sql" <<'SQL'
-- allow-destructive: v_old_summary 참조 코드는 V50 릴리스에서 제거 완료 — contract 단계
ALTER TABLE app.example DROP COLUMN note;
SQL
expect 0 "allow-destructive 승인 주석 통과" "$TMP/allowed.sql"

cat > "$TMP/not-null-default.sql" <<'SQL'
ALTER TABLE app.example ADD COLUMN flag boolean NOT NULL DEFAULT false;
SQL
expect 0 "ADD COLUMN NOT NULL DEFAULT 는 통과(구버전 INSERT 안전)" "$TMP/not-null-default.sql"

cat > "$TMP/multiline.sql" <<'SQL'
ALTER TABLE app.example
  DROP
  COLUMN note;
SQL
expect 1 "여러 줄에 걸친 DROP COLUMN 차단" "$TMP/multiline.sql"

echo "셀프테스트: $pass/$total"
[ "$pass" -eq "$total" ]
