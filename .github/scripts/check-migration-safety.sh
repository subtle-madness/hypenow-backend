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
# v3 (07-30): Flyway 버전 번호 중복 검사 — PR #181이 V43(랜딩 통계)을 들고 있는 사이 develop이
# V43(trait 어휘)을 선점, 그대로 머지되면 같은 버전 2개로 Flyway 기동이 거부된다(V18·V43에
# 이어 3번째 재발). PR 브랜치 자기 트리만 봐서는 못 잡는다 — 그 브랜치엔 V43이 1개뿐이라
# 충돌은 base와 합쳐질 때만 드러난다. 그래서 이 검사는 base ref와 HEAD의 트리 스냅샷을
# 직접 대조한다(CI가 base를 이미 인자로 넘긴다 — ci.yml
# `check-migration-safety.sh "origin/${{ github.base_ref }}"`).
#
# **스코프가 파괴적 DDL 검사와 다르다(의도).** 파괴적 DDL 검사는 was 롤링 공존 근거가 있는
# analysis DB(was app + analytics)만 본다. 버전 중복은 근거가 다르다 — 어느 Flyway
# 인스턴스든 중복 버전이 있으면 그 인스턴스 자체가 기동을 거부한다(신구 공존 여부와
# 무관한 실패 모드). Flyway 인스턴스는 4개이고 각각 독립 버전 공간(별도 히스토리
# 테이블)이므로 디렉토리별로 독립 검사한다 — was의 V1과 analytics의 V1은 정상. crawler·
# monitoring도 포함해 4개 전부를 대상으로 한다(crawler는 파괴적 DDL 검사 스코프 밖이지만
# 버전 중복 검사는 별개 실패 모드라 대상).
#
# v3.1 (07-30): ci.yml `test` 잡에 v3 이전부터 있던 인라인 버전 중복 검사(현재 트리 하나만
# `ls`+`uniq -d`로 훑는 단순 버전 — monitoring 누락·선행 0 미정규화)를 이 스크립트로 통합했다.
# 실측 결과 그 인라인 검사는 로직상 #181을 잡을 수 있었다(PR CI가 `refs/pull/N/merge`를
# 체크아웃해 머지 트리에 V43 2개가 보였을 것) — 실제 사고 원인은 검사 부재가 아니라
# **재실행 부재**였다(#181 CI 실행은 base가 V43-trait을 얻기 전, 그 뒤 재실행이 없었다).
# 이 통합은 그 레이스를 고치지 못한다(고치려면 브랜치 보호 룰셋의 required+strict가 필요,
# 코드 범위 밖) — 얻는 건 **단일 구현**(로직이 셀프테스트로 보호됨)·monitoring 포함·선행 0
# 정규화·그리고 base 대조 모드(`--versions`, PR CI 전용 `migration-guard` 잡)뿐이다.
# `test` 잡은 push 이벤트에서도 돌아 base_ref가 없으므로, 그 경로는 "현재 트리 내부 중복"만
# 검사한다(`--versions-tree`) — base 대조 없이도 잡히는 실패 모드(같은 트리 안에서 번호가
# 겹치는 사고)에는 여전히 유효하다.
#
# v3.2 (07-30): 신규 마이그레이션은 UTC 타임스탬프로 채번(`V<YYYYMMDDHHMMSS>__`, CLAUDE.md
# 컨벤션 절)해 애초에 경합할 다음 정수 번호가 없게 한다 — 이 검사 자체는 **무수정**으로
# 호환된다: `normalize_version`의 정규식(`^V[0-9]+(\.[0-9]+)*__`)과 `10#$p` 산술 둘 다
# 자릿수 제한이 없고, Flyway 자신도 버전을 정수(BigInteger)로 비교하므로 14자리 타임스탬프는
# 기존 `V1`~`V49`류보다 항상 크다(`MigrationVersion.compareTo` 실측 확인). 기존 파일은 rename
# 금지(schema_history 체크섬 고정)이므로 정수·타임스탬프가 각 디렉토리 안에 영구 공존한다 —
# 셀프테스트에 그 공존·충돌 케이스를 추가했다(check-migration-safety.test.sh).
#
# v4 (08-13): 신규 채번 질서 검사 — 08-12 monitoring 크래시루프 2연장(KST 채번이 미래 번호를
# 선점 → 이후 UTC 정상 채번이 전부 Flyway out-of-order 거부) 재발 방지. base 목록에 없는
# 신규 파일만 대상으로 ①번호가 자기 디렉토리 base 최대 이하면 차단(역전 — 심긴 지뢰가
# 터지는 걸 막는다) ②14자리 타임스탬프가 현재 UTC+1h를 넘으면 차단(미래 채번 — 지뢰를
# 심는 것 자체를 막는다. +1h는 분 올림 관행 허용 오차). 의도된 미래 번호(#455처럼 이미 DB에
# 박힌 미래 번호 위로 올라가는 핫픽스)는 파일에 `-- allow-future-version: <사유>` 주석으로
# 통과시킨다(allow-destructive와 같은 관용구). --versions-tree(push 경로)에서는 안 돈다 —
# base 없이는 "신규"를 구분할 수 없고, PR·merge_group 경로가 이미 전수 커버한다.
#
# 사용법: check-migration-safety.sh <base-ref>                             # git diff 기반 (CI, PR 전용)
#         check-migration-safety.sh --scan <파일…>                          # 파괴적 DDL만 파일 직접 검사 (셀프테스트용)
#         check-migration-safety.sh --versions <base-목록파일> <head-목록파일>    # 버전 중복 base 대조 (셀프테스트용)
#         check-migration-safety.sh --versions-tree [<루트경로>]              # 버전 중복 트리 단독 검사 (CI push 경로 + 셀프테스트용, git 비의존)
#         check-migration-safety.sh --ordering <base-목록파일> <head-목록파일> [<콘텐츠루트>]  # 신규 채번 질서 검사 (셀프테스트용 — now는 MIGRATION_GUARD_NOW로 주입)
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

# 버전 중복 검사 대상 — Flyway 인스턴스 4개 각각의 마이그레이션 디렉토리(위 v3 주석 참고).
VERSION_DIRS=(
  analytics/src/main/resources/db/migration/analysis
  was/src/main/resources/db/migration/app
  crawler/src/main/resources/db/migration
  monitoring/src/main/resources/db/migration
)

# 파일명 "V<버전>__<설명>.sql"에서 버전을 뽑아 정규화한다. Flyway는 버전을 숫자로 비교하므로
# V07과 V7은 같은 버전 — 컴포넌트별(점 구분. 이 저장소는 현재 정수만 쓰지만 방어) 선행 0을
# 제거해 비교 가능한 문자열로 만든다. 버전 패턴이 없는 파일명(R__ 등)은 빈 문자열을 반환 —
# 호출부에서 건너뛴다.
normalize_version() {
  local base="$1" ver
  ver="$(printf '%s\n' "$base" | grep -oE '^V[0-9]+(\.[0-9]+)*__' || true)"
  if [ -z "$ver" ]; then
    printf ''
    return 0
  fi
  ver="${ver#V}"
  ver="${ver%__}"
  local IFS='.'
  local -a parts=($ver)
  local -a out=()
  local p
  for p in "${parts[@]}"; do
    out+=("$((10#$p))")
  done
  printf '%s' "${out[*]}"
}

version_dirname() {
  case "$1" in
    */*) printf '%s' "${1%/*}" ;;
    *) printf '.' ;;
  esac
}

version_basename() {
  printf '%s' "${1##*/}"
}

# <dir> <work-디렉토리> <필요 개수> — 그 디렉토리의 base+HEAD 전체에서 쓰이지 않는 다음 정수
# 버전 번호를 필요한 개수만큼 제안한다(에러 메시지 보조 — 정수부만 사용, 점 버전은 방어적
# 파싱 대상일 뿐 이 저장소에 실존하지 않아 제안 대상 아님).
next_free_versions() {
  local dir="$1" work="$2" count="$3" max=0 v intpart
  while IFS= read -r v; do
    [ -n "$v" ] || continue
    intpart="${v%%.*}"
    if [ "$intpart" -gt "$max" ] 2>/dev/null; then
      max=$intpart
    fi
  done < <(awk -F'\t' -v d="$dir" '$1==d{print $2}' "$work/base.tsv" "$work/head.tsv" 2>/dev/null)

  local out="" i next
  for ((i = 1; i <= count; i++)); do
    next=$((max + i))
    if [ -z "$out" ]; then out="V$next"; else out="$out, V$next"; fi
  done
  printf '%s' "$out"
}

# <base-목록파일> <head-목록파일>(경로 1행 1개, 없거나 빈 파일도 허용)을 받아 디렉토리별로
# ①HEAD 트리 내부에서 같은 버전이 서로 다른 파일명으로 2개 이상 있으면 차단
# ②같은 (디렉토리,버전)이 base·HEAD 양쪽에 있는데 파일명이 다르면 차단(교차 브랜치 충돌 — #181 케이스)
# base·HEAD의 파일명이 같으면(변경 없음/내용만 수정) 정상 처리한다.
# git 의존 없이 순수 파일 목록만으로 동작 — CI 경로(git ls-tree)와 --versions 셀프테스트가
# 이 함수 하나를 공유해 로직 중복이 없다. 반환 0=통과 1=위반.
# 파일 목록(경로 1행 1개, 없거나 빈 파일 허용)을 "dir<TAB>정규화버전<TAB>파일명" tsv로 변환 —
# check_versions·check_ordering이 공유한다(v4에서 추출).
build_version_tsv() { # <목록파일> <출력tsv>
  local list_file="$1" out_file="$2" line d b v
  : > "$out_file"
  [ -f "$list_file" ] || return 0
  while IFS= read -r line; do
    [ -n "$line" ] || continue
    b="$(version_basename "$line")"
    v="$(normalize_version "$b")"
    [ -n "$v" ] || continue
    d="$(version_dirname "$line")"
    printf '%s\t%s\t%s\n' "$d" "$v" "$b" >> "$out_file"
  done < "$list_file"
}

check_versions() {
  local base_file="$1" head_file="$2" rc=0
  local work
  work="$(mktemp -d)"
  trap "rm -rf '$work'" RETURN

  build_version_tsv "$base_file" "$work/base.tsv"
  build_version_tsv "$head_file" "$work/head.tsv"

  # ① HEAD 트리 내부 중복 — 같은 (dir,version)에 서로 다른 파일명이 2개 이상
  local dup
  dup="$(awk -F'\t' '
    {
      key = $1 SUBSEP $2
      namekey = key SUBSEP $3
      if (!(namekey in seen)) {
        seen[namekey] = 1
        count[key]++
        if (count[key] == 1) { namelist[key] = $3; order[++n] = key }
        else { namelist[key] = namelist[key] ", " $3 }
      }
    }
    END {
      for (i = 1; i <= n; i++) {
        k = order[i]
        if (count[k] > 1) {
          split(k, parts, SUBSEP)
          print parts[1] "\t" parts[2] "\t" namelist[k]
        }
      }
    }
  ' "$work/head.tsv")"

  if [ -n "$dup" ]; then
    while IFS=$'\t' read -r d v names; do
      [ -n "$d" ] || continue
      echo "::error::마이그레이션 버전 중복 (같은 브랜치 안, $d): 버전 $v — 파일 [$names]. 뒤에 추가한 파일을 다음 빈 번호로 rename 하세요 → 후보: $(next_free_versions "$d" "$work" 2)"
      rc=1
    done <<< "$dup"
  fi

  # ② base↔HEAD 교차 충돌 — 같은 (dir,version)이 base에도 있는데 파일명이 다르면(#181 케이스)
  local cross
  cross="$(awk -F'\t' '
    FNR == NR {
      basekey = $1 SUBSEP $2
      baseset[basekey] = (basekey in baseset) ? baseset[basekey] "," $3 : $3
      next
    }
    {
      key = $1 SUBSEP $2
      if (key in baseset) {
        n = split(baseset[key], arr, ",")
        conflict = 0
        for (i = 1; i <= n; i++) if (arr[i] != $3) conflict = 1
        if (conflict) print $1 "\t" $2 "\t" $3 "\t" baseset[key]
      }
    }
  ' "$work/base.tsv" "$work/head.tsv")"

  if [ -n "$cross" ]; then
    while IFS=$'\t' read -r d v headname basenames; do
      [ -n "$d" ] || continue
      echo "::error::마이그레이션 버전 중복 (base와 충돌, $d): 버전 $v — HEAD의 '$headname'이 base의 '$basenames'와 같은 번호입니다. HEAD 쪽 파일을 다음 빈 번호로 rename 하세요 → 후보: $(next_free_versions "$d" "$work" 1)"
      rc=1
    done <<< "$cross"
  fi

  return $rc
}

# v4: 신규 채번 질서 검사(파일 헤더 주석 참고). base 목록에 없는 (dir,파일명)만 "신규"로 보고
# ①자기 디렉토리 base 최대 이하 번호 차단(역전 — Flyway out-of-order 거부 재현 방지)
# ②14자리 타임스탬프의 미래 채번 차단(now+1h 초과 — KST 채번은 +9h라 반드시 걸린다.
#   +1h 산술은 자릿수 덧셈(+10000)이라 23시대→익일 경계에서는 오차가 0으로 줄어드는
#   보수적 방향의 부정확성만 있다). 승인 주석은 <콘텐츠루트>/<dir>/<파일명>에서 읽는다.
# now는 MIGRATION_GUARD_NOW(UTC 14자리)로 주입 가능 — 셀프테스트 결정성용. 반환 0=통과 1=위반.
check_ordering() { # <base-목록파일> <head-목록파일> <콘텐츠루트>
  local base_file="$1" head_file="$2" root="$3" rc=0
  local work
  work="$(mktemp -d)"
  trap "rm -rf '$work'" RETURN

  build_version_tsv "$base_file" "$work/base.tsv"
  build_version_tsv "$head_file" "$work/head.tsv"

  local now limit
  now="${MIGRATION_GUARD_NOW:-$(date -u +%Y%m%d%H%M%S)}"
  limit=$((10#$now + 10000))

  local d v b intpart dirmax
  while IFS=$'\t' read -r d v b; do
    [ -n "$d" ] || continue
    # base에 같은 (dir,파일명)이 있으면 기존 파일 — 신규만 검사한다(기존 파일은 rename 금지
    # 규약이라 번호를 고칠 수도 없고, 이미 히스토리에 적용돼 있어 검사 의미가 없다)
    if awk -F'\t' -v d="$d" -v b="$b" '$1==d && $3==b{found=1} END{exit !found}' "$work/base.tsv"; then
      continue
    fi
    intpart="${v%% *}"
    # ① 역전 검사 — 정수부 기준(이 저장소는 점 버전을 쓰지 않는다, next_free_versions와 동일 전제)
    dirmax="$(awk -F'\t' -v d="$d" 'BEGIN{max=0} $1==d{split($2,a," "); if (a[1]+0>max) max=a[1]+0} END{print max}' "$work/base.tsv")"
    if [ "$((10#$intpart))" -le "$dirmax" ]; then
      echo "::error::채번 역전($d): 신규 '$b'(버전 $intpart)이 base 최대($dirmax) 이하 — 머지되면 Flyway가 out-of-order로 기동을 거부합니다(08-12 monitoring 크래시루프 사고). 현재 UTC 타임스탬프(V<YYYYMMDDHHMMSS>__, date -u +%Y%m%d%H%M%S)로 rename 하세요."
      rc=1
      continue
    fi
    # ② 미래 채번 검사 — 14자리(타임스탬프 채번)만 대상. 정수 연번은 ①이 커버한다.
    if [ "${#intpart}" -eq 14 ] && [ "$((10#$intpart))" -gt "$limit" ]; then
      if [ -f "$root/$d/$b" ] && grep -qiE '^[[:space:]]*--[[:space:]]*allow-future-version:' "$root/$d/$b"; then
        echo "SKIP(미래 채번) $d/$b — allow-future-version 승인 주석"
      else
        echo "::error file=$d/$b::미래 시각 채번($d): '$b'(버전 $intpart)이 현재 UTC+1h($limit)를 초과 — KST 채번 의심(채번 규약은 UTC, CLAUDE.md). 현재 UTC로 rename 하거나, 이미 DB에 적용된 미래 번호 위로 올라가는 의도적 채번이면 파일에 '-- allow-future-version: <사유>' 주석을 추가하세요."
        rc=1
      fi
    fi
  done < "$work/head.tsv"
  return $rc
}

fail=0
if [ "${1:-}" = "--scan" ]; then
  shift
  for f in "$@"; do scan_file "$f" || fail=1; done
elif [ "${1:-}" = "--versions" ]; then
  BASE_FILE="${2:?사용법: check-migration-safety.sh --versions <base-목록파일> <head-목록파일>}"
  HEAD_FILE="${3:?사용법: check-migration-safety.sh --versions <base-목록파일> <head-목록파일>}"
  if check_versions "$BASE_FILE" "$HEAD_FILE"; then
    echo "OK   버전 중복 없음"
  else
    fail=1
  fi
elif [ "${1:-}" = "--ordering" ]; then
  BASE_FILE="${2:?사용법: check-migration-safety.sh --ordering <base-목록파일> <head-목록파일> [<콘텐츠루트>]}"
  HEAD_FILE="${3:?사용법: check-migration-safety.sh --ordering <base-목록파일> <head-목록파일> [<콘텐츠루트>]}"
  ROOT="${4:-.}"
  if check_ordering "$BASE_FILE" "$HEAD_FILE" "$ROOT"; then
    echo "OK   채번 질서 위반 없음"
  else
    fail=1
  fi
elif [ "${1:-}" = "--versions-tree" ]; then
  # base 대조가 불가능한 경로(push 이벤트 — base_ref 없음, 얕은 클론)를 위한 트리 단독
  # 검사. git을 쓰지 않고 파일시스템만 본다 — check_versions의 "HEAD 트리 내부 중복" 로직을
  # base 쪽을 빈 목록으로 넘겨 그대로 재사용한다(교차 충돌 쪽은 base가 비어 자연히 무동작).
  ROOT="${2:-.}"
  VTTMP="$(mktemp -d)"
  trap 'rm -rf "$VTTMP"' EXIT
  : > "$VTTMP/empty.list"
  : > "$VTTMP/tree.list"
  for d in "${VERSION_DIRS[@]}"; do
    dirpath="$ROOT/$d"
    [ -d "$dirpath" ] || continue
    while IFS= read -r -d '' f; do
      printf '%s/%s\n' "$d" "$(basename "$f")" >> "$VTTMP/tree.list"
    done < <(find "$dirpath" -maxdepth 1 -type f -print0 2>/dev/null)
  done
  if check_versions "$VTTMP/empty.list" "$VTTMP/tree.list"; then
    echo "OK   버전 중복 없음 (트리: $ROOT)"
  else
    fail=1
  fi
else
  BASE="${1:?사용법: check-migration-safety.sh <base-ref> | --scan <파일…> | --versions <base-목록파일> <head-목록파일> | --versions-tree [<루트경로>]}"
  while IFS= read -r f; do
    [ -n "$f" ] && [ -f "$f" ] || continue
    scan_file "$f" || fail=1
  done < <(git diff --name-only --diff-filter=AM "$BASE...HEAD" -- \
    'was/src/main/resources/db/migration/*.sql' \
    'analytics/src/main/resources/db/migration/analysis/*.sql')

  # 버전 중복 검사 — 4개 디렉토리 전부(위 v3 주석). diff가 아니라 base·HEAD 각각의 트리
  # 스냅샷(git ls-tree)을 직접 비교한다 — diff 기반이면 "이번 PR이 안 건드린 기존 파일과의
  # 충돌"은 diff에 안 잡히므로 놓친다. 스냅샷 비교라야 base에 먼저 들어온 파일과도 대조된다.
  VTMP="$(mktemp -d)"
  trap 'rm -rf "$VTMP"' EXIT
  git ls-tree -r --name-only "$BASE" -- "${VERSION_DIRS[@]}" > "$VTMP/base.list" 2>/dev/null || true
  git ls-tree -r --name-only HEAD -- "${VERSION_DIRS[@]}" > "$VTMP/head.list" 2>/dev/null || true
  if check_versions "$VTMP/base.list" "$VTMP/head.list"; then
    echo "OK   버전 중복 없음 (기준: $BASE)"
  else
    fail=1
  fi

  # v4: 신규 채번 질서 검사 — 같은 스냅샷 재사용. 콘텐츠 루트는 체크아웃된 HEAD 트리(=CWD) —
  # 신규 파일의 allow-future-version 승인 주석을 여기서 읽는다.
  if check_ordering "$VTMP/base.list" "$VTMP/head.list" .; then
    echo "OK   채번 질서 위반 없음 (기준: $BASE)"
  else
    fail=1
  fi

  echo "검사 완료 (기준: $BASE...HEAD)"
fi
exit $fail
