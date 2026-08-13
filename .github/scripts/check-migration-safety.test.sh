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

cat > "$TMP/contract-with-backfill.sql" <<'SQL'
-- allow-destructive: note 참조 코드는 릴리스 N에서 제거 완료 — contract 단계
UPDATE app.example SET memo = COALESCE(memo, note);
ALTER TABLE app.example DROP COLUMN note;
SQL
expect 0 "contract(보정 UPDATE 동봉) 통과" "$TMP/contract-with-backfill.sql"

cat > "$TMP/contract-no-backfill-tag.sql" <<'SQL'
-- allow-destructive: 미러 소유 컬럼 — 참조 코드 릴리스 N에서 제거 완료
-- no-backfill: 미러가 매일 전체 재기록 — 창 유실 없음
ALTER TABLE app.example DROP COLUMN note;
SQL
expect 0 "contract(no-backfill 사유 명시) 통과" "$TMP/contract-no-backfill-tag.sql"

cat > "$TMP/contract-missing-backfill.sql" <<'SQL'
-- allow-destructive: note 참조 코드는 릴리스 N에서 제거 완료
ALTER TABLE app.example DROP COLUMN note;
SQL
expect 1 "contract에 보정 UPDATE도 no-backfill 태그도 없으면 차단" "$TMP/contract-missing-backfill.sql"

cat > "$TMP/contract-wrong-backfill.sql" <<'SQL'
-- allow-destructive: note 참조 코드는 릴리스 N에서 제거 완료
UPDATE app.example SET memo = other_column;
ALTER TABLE app.example DROP COLUMN note;
SQL
expect 1 "보정 UPDATE가 drop 대상 컬럼을 참조 안 하면 차단" "$TMP/contract-wrong-backfill.sql"

cat > "$TMP/contract-drop-table.sql" <<'SQL'
-- allow-destructive: example_old 참조 코드는 릴리스 N에서 제거 완료
DROP TABLE app.example_old;
SQL
expect 0 "DROP TABLE은 짝 검사 대상 아님(컬럼 이행이 아님)" "$TMP/contract-drop-table.sql"

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

# --versions 모드 — 버전 중복 검사(v3). 파일 단위가 아니라 집합 단위라 --scan과 별도 seam.
expect_versions() { # expect_versions <기대코드> <설명> <base-목록> <head-목록>
  local want="$1" desc="$2" base="$3" head="$4" got=0
  total=$((total+1))
  "$SCRIPT" --versions "$base" "$head" >/dev/null 2>&1 || got=$?
  if [ "$got" -eq "$want" ]; then
    pass=$((pass+1)); echo "ok   $desc"
  else
    echo "FAIL $desc — 기대 $want, 실제 $got"
  fi
}

DIR_A=analytics/src/main/resources/db/migration/analysis
DIR_B=was/src/main/resources/db/migration/app

printf '%s/V43__trait_taxonomy_makeup_review.sql\n' "$DIR_A" > "$TMP/v-base-collision.txt"
printf '%s/V43__landing_stats_nano_band.sql\n' "$DIR_A" > "$TMP/v-head-collision.txt"
expect_versions 1 "base V43(trait)·head V43(landing) 교차 충돌 차단 (#181 재현)" \
  "$TMP/v-base-collision.txt" "$TMP/v-head-collision.txt"

printf '%s/V43__trait.sql\n' "$DIR_A" > "$TMP/v-base-nextver.txt"
printf '%s/V44__perf.sql\n' "$DIR_A" > "$TMP/v-head-nextver.txt"
expect_versions 0 "base V43·head V44는 통과" "$TMP/v-base-nextver.txt" "$TMP/v-head-nextver.txt"

printf '%s/V43__trait.sql\n' "$DIR_A" > "$TMP/v-base-same.txt"
printf '%s/V43__trait.sql\n' "$DIR_A" > "$TMP/v-head-same.txt"
expect_versions 0 "base·head 동일 파일명 V43은 통과(변경 없음)" "$TMP/v-base-same.txt" "$TMP/v-head-same.txt"

: > "$TMP/v-base-empty.txt"
printf '%s/V43__a.sql\n%s/V43__b.sql\n' "$DIR_A" "$DIR_A" > "$TMP/v-head-dup.txt"
expect_versions 1 "HEAD 트리 내 V43 2개는 통과 아님(차단)" "$TMP/v-base-empty.txt" "$TMP/v-head-dup.txt"

printf '%s/V1__init.sql\n' "$DIR_B" > "$TMP/v-base-crossdir.txt"
printf '%s/V1__init.sql\n%s/V1__init.sql\n' "$DIR_B" "$DIR_A" > "$TMP/v-head-crossdir.txt"
expect_versions 0 "다른 디렉토리의 같은 번호(app V1 + analysis V1)는 통과(독립 공간)" \
  "$TMP/v-base-crossdir.txt" "$TMP/v-head-crossdir.txt"

printf 'crawler/src/main/resources/db/migration/V07__a.sql\n' > "$TMP/v-base-leadingzero.txt"
printf 'crawler/src/main/resources/db/migration/V7__b.sql\n' > "$TMP/v-head-leadingzero.txt"
expect_versions 1 "V07과 V7은 같은 버전으로 취급되어 차단" \
  "$TMP/v-base-leadingzero.txt" "$TMP/v-head-leadingzero.txt"

expect_versions 0 "빈 목록/신규 디렉토리는 통과(크래시 금지)" \
  "$TMP/does-not-exist-base.txt" "$TMP/does-not-exist-head.txt"

# 타임스탬프 버전(V<YYYYMMDDHHMMSS>__, 07-30~ 신규 컨벤션) — 정규식·정규화 둘 다 자릿수 제한이
# 없어 기존 정수 연번과 무수정으로 공존해야 한다(CLAUDE.md 컨벤션 절, deploy/README.md §5-1 v3.2).
printf '%s/V49__existing.sql\n' "$DIR_A" > "$TMP/v-base-ts-coexist.txt"
printf '%s/V49__existing.sql\n%s/V20260730153000__new_timestamp.sql\n' "$DIR_A" "$DIR_A" > "$TMP/v-head-ts-coexist.txt"
expect_versions 0 "기존 정수 V49와 신규 타임스탬프 V20260730153000는 충돌 없이 공존" \
  "$TMP/v-base-ts-coexist.txt" "$TMP/v-head-ts-coexist.txt"

printf '%s/V20260730100000__a.sql\n' "$DIR_A" > "$TMP/v-base-ts-collision.txt"
printf '%s/V20260730100000__b.sql\n' "$DIR_A" > "$TMP/v-head-ts-collision.txt"
expect_versions 1 "같은 초에 채번된 타임스탬프 버전 2개도 여전히 충돌로 차단(동일 (dir,버전) 다른 파일명)" \
  "$TMP/v-base-ts-collision.txt" "$TMP/v-head-ts-collision.txt"

printf '%s/V20260730100000__same.sql\n' "$DIR_A" > "$TMP/v-base-ts-same.txt"
printf '%s/V20260730100000__same.sql\n' "$DIR_A" > "$TMP/v-head-ts-same.txt"
expect_versions 0 "base·head 동일 타임스탬프 파일명은 통과(변경 없음)" \
  "$TMP/v-base-ts-same.txt" "$TMP/v-head-ts-same.txt"

# --versions-tree 모드 — base 대조가 불가능한 경로(push 이벤트, 얕은 클론)용 트리 단독 검사.
# git 비의존 — 임시 디렉토리에 실제 파일을 만들어 검사한다.
expect_tree() { # expect_tree <기대코드> <설명> <트리루트>
  local want="$1" desc="$2" root="$3" got=0
  total=$((total+1))
  "$SCRIPT" --versions-tree "$root" >/dev/null 2>&1 || got=$?
  if [ "$got" -eq "$want" ]; then
    pass=$((pass+1)); echo "ok   $desc"
  else
    echo "FAIL $desc — 기대 $want, 실제 $got"
  fi
}

TREE="$TMP/tree"
mkdir -p "$TREE/$DIR_A" "$TREE/$DIR_B" \
  "$TREE/crawler/src/main/resources/db/migration"
# monitoring 디렉토리는 의도적으로 안 만든다 — "디렉토리가 아예 없음" 케이스 겸용

: > "$TREE/$DIR_A/V43__trait.sql"
: > "$TREE/$DIR_B/V14__signup_events.sql"
expect_tree 0 "정상 트리(중복 없음) 통과" "$TREE"

: > "$TREE/$DIR_A/V44__dup_a.sql"
: > "$TREE/$DIR_A/V44__dup_b.sql"
expect_tree 1 "한 디렉토리에 같은 번호 2개는 차단" "$TREE"
rm -f "$TREE/$DIR_A/V44__dup_a.sql" "$TREE/$DIR_A/V44__dup_b.sql"

: > "$TREE/$DIR_B/V1__init.sql"
: > "$TREE/$DIR_A/V1__init.sql"
expect_tree 0 "서로 다른 디렉토리의 같은 번호(app V1 + analysis V1)는 통과" "$TREE"
rm -f "$TREE/$DIR_B/V1__init.sql" "$TREE/$DIR_A/V1__init.sql"

: > "$TREE/crawler/src/main/resources/db/migration/V07__a.sql"
: > "$TREE/crawler/src/main/resources/db/migration/V7__b.sql"
expect_tree 1 "V07/V7 동시 존재는 차단" "$TREE"
rm -f "$TREE/crawler/src/main/resources/db/migration"/*.sql

expect_tree 0 "디렉토리가 아예 없음(신규 루트)은 통과(크래시 금지)" "$TMP/no-such-root"

: > "$TREE/$DIR_A/V49__existing.sql"
: > "$TREE/$DIR_A/V20260730153000__new_timestamp.sql"
expect_tree 0 "트리 단독 검사에서도 정수 V49 + 타임스탬프 V20260730153000 공존 통과" "$TREE"
rm -f "$TREE/$DIR_A/V49__existing.sql" "$TREE/$DIR_A/V20260730153000__new_timestamp.sql"

# --ordering 모드 — 신규 마이그레이션 채번 질서 검사(v4): ①미래 시각 채번 차단(KST 채번 사고
# 08-12 1차 재현) ②base 최대 번호 이하의 신규 채번 차단(Flyway out-of-order 거부 — 08-12 2차
# 재현). "신규"는 base 목록에 없는 (디렉토리,파일명)만 — 기존 파일은 검사 대상이 아니다.
# now는 MIGRATION_GUARD_NOW(UTC 14자리)로 주입해 결정적으로 검사한다.
expect_ordering() { # expect_ordering <기대코드> <설명> <base-목록> <head-목록> <콘텐츠루트> <now>
  local want="$1" desc="$2" base="$3" head="$4" root="$5" now="$6" got=0
  total=$((total+1))
  MIGRATION_GUARD_NOW="$now" "$SCRIPT" --ordering "$base" "$head" "$root" >/dev/null 2>&1 || got=$?
  if [ "$got" -eq "$want" ]; then
    pass=$((pass+1)); echo "ok   $desc"
  else
    echo "FAIL $desc — 기대 $want, 실제 $got"
  fi
}

DIR_M=monitoring/src/main/resources/db/migration
ORD_ROOT="$TMP/ord-root"
mkdir -p "$ORD_ROOT/$DIR_M" "$ORD_ROOT/$DIR_A"

printf '%s/V20260812111153__ok.sql\n' "$DIR_M" > "$TMP/o-base-normal.txt"
printf '%s/V20260812111153__ok.sql\n%s/V20260813020000__new.sql\n' "$DIR_M" "$DIR_M" > "$TMP/o-head-normal.txt"
expect_ordering 0 "정상 채번(base 최대보다 크고 과거 시각)은 통과" \
  "$TMP/o-base-normal.txt" "$TMP/o-head-normal.txt" "$ORD_ROOT" 20260813023000

printf '%s/V20260812170000__hotfix.sql\n' "$DIR_M" > "$TMP/o-base-mono.txt"
printf '%s/V20260812170000__hotfix.sql\n%s/V20260812120216__late.sql\n' "$DIR_M" "$DIR_M" > "$TMP/o-head-mono.txt"
expect_ordering 1 "base 최대(170000)보다 작은 신규 채번(120216)은 차단 (08-12 2차 사고 재현)" \
  "$TMP/o-base-mono.txt" "$TMP/o-head-mono.txt" "$ORD_ROOT" 20260812130000

printf '%s/V49__existing.sql\n' "$DIR_M" > "$TMP/o-base-future.txt"
printf '%s/V49__existing.sql\n%s/V20260812153000__kst.sql\n' "$DIR_M" "$DIR_M" > "$TMP/o-head-future.txt"
expect_ordering 1 "미래 시각 채번(KST +9h)은 차단 (08-12 1차 사고 재현)" \
  "$TMP/o-base-future.txt" "$TMP/o-head-future.txt" "$ORD_ROOT" 20260812063000

cat > "$ORD_ROOT/$DIR_M/V20260812180000__escape.sql" <<'SQL'
-- allow-future-version: 미래 번호(170000)가 이미 운영 DB에 적용됨 — 그 위로 올라가는 핫픽스
ALTER TABLE brand ADD COLUMN note text;
SQL
printf '%s/V20260812170000__hotfix.sql\n' "$DIR_M" > "$TMP/o-base-escape.txt"
printf '%s/V20260812170000__hotfix.sql\n%s/V20260812180000__escape.sql\n' "$DIR_M" "$DIR_M" > "$TMP/o-head-escape.txt"
expect_ordering 0 "미래 채번도 allow-future-version 승인 주석이 있으면 통과 (#455 핫픽스 케이스)" \
  "$TMP/o-base-escape.txt" "$TMP/o-head-escape.txt" "$ORD_ROOT" 20260812130000

printf '%s/V20260812180000__mine.sql\n' "$DIR_M" > "$TMP/o-base-unchanged.txt"
printf '%s/V20260812180000__mine.sql\n' "$DIR_M" > "$TMP/o-head-unchanged.txt"
expect_ordering 0 "기존 파일은 번호가 미래여도 검사 대상 아님(신규만 검사)" \
  "$TMP/o-base-unchanged.txt" "$TMP/o-head-unchanged.txt" "$ORD_ROOT" 20260812130000

printf '%s/V49__existing.sql\n' "$DIR_M" > "$TMP/o-base-round.txt"
printf '%s/V49__existing.sql\n%s/V20260813030000__rounded.sql\n' "$DIR_M" "$DIR_M" > "$TMP/o-head-round.txt"
expect_ordering 0 "1시간 이내 앞선 채번(분 올림 관행)은 허용 오차로 통과" \
  "$TMP/o-base-round.txt" "$TMP/o-head-round.txt" "$ORD_ROOT" 20260813023000

printf '%s/V20260812111153__ts.sql\n' "$DIR_M" > "$TMP/o-base-int.txt"
printf '%s/V20260812111153__ts.sql\n%s/V50__integer.sql\n' "$DIR_M" "$DIR_M" > "$TMP/o-head-int.txt"
expect_ordering 1 "타임스탬프 체제 이후의 신규 정수 채번(V50)은 단조 증가 위반으로 차단" \
  "$TMP/o-base-int.txt" "$TMP/o-head-int.txt" "$ORD_ROOT" 20260813023000

: > "$TMP/o-base-empty.txt"
printf '%s/V20260813020000__first.sql\n' "$DIR_M" > "$TMP/o-head-firstfile.txt"
expect_ordering 0 "빈 base(신규 디렉토리)의 과거 시각 채번은 통과" \
  "$TMP/o-base-empty.txt" "$TMP/o-head-firstfile.txt" "$ORD_ROOT" 20260813023000

printf '%s/V20260813020000__m.sql\n%s/V43__a.sql\n' "$DIR_M" "$DIR_A" > "$TMP/o-base-crossdir.txt"
printf '%s/V20260813020000__m.sql\n%s/V43__a.sql\n%s/V44__b.sql\n' "$DIR_M" "$DIR_A" "$DIR_A" > "$TMP/o-head-crossdir.txt"
expect_ordering 0 "버전 공간은 디렉토리별 독립 — 옆 디렉토리의 타임스탬프 최대와 비교하지 않고 자기 디렉토리 최대(V43)만 기준(V44 통과)" \
  "$TMP/o-base-crossdir.txt" "$TMP/o-head-crossdir.txt" "$ORD_ROOT" 20260813023000

echo "셀프테스트: $pass/$total"
[ "$pass" -eq "$total" ]
