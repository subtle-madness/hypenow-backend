#!/usr/bin/env bash
# 레포 잔재 정리(GC): 머지된 워크트리·브랜치를 기계적으로 치우고, 남은 것은 표로 보고한다.
#
# 배경(09-03): 세션마다 .claude/worktrees/·.worktrees/에 워크트리를 만들고 "끝나면 정리해라"는
# 지침은 매번 무시돼 워크트리 35개·로컬 브랜치 71개(머지분 40개)가 쌓였다. 사람/모델이 기억하는
# 대신 이 스크립트가 SessionStart 훅에서 --auto로 돈다.
#
# 사용:
#   scripts/repo-gc.sh                 # 보고만(드라이런)
#   scripts/repo-gc.sh --auto          # 안전한 것만 삭제: 머지됨 + 클린 + 살아있는 프로세스 없음
#   scripts/repo-gc.sh --auto --force-dirty
#                                      # 머지됐지만 미커밋 변경이 있는 워크트리도 패치를
#                                      # .git/gc-trash/ 에 저장한 뒤 삭제
#   scripts/repo-gc.sh --quiet         # 훅용: 삭제했거나 잔재가 남았을 때만 출력
#   scripts/repo-gc.sh --no-gh         # gh(스쿼시 머지 판정) 호출 생략 — 오프라인용
#
# 삭제 안전 규칙(전부 만족해야 --auto가 지운다):
#   1. 머지됨 — origin/develop 조상이거나, gh PR 상태 MERGED(스쿼시)이거나, git cherry 잔여 0
#   2. 워크트리 클린(미커밋·미추적 없음) — --force-dirty 시 패치 보존 후 예외
#   3. 그 디렉토리를 cwd로 잡은 프로세스 없음(열린 세션·셸) — 있으면 절대 안 건드림
#   4. 현재 셸의 cwd가 그 워크트리 안이 아님
# 브랜치(워크트리 없는 것)는 1번만 만족하면 지운다. 미머지 브랜치·워크트리는 절대 안 지운다.
set -euo pipefail

AUTO=0; FORCE_DIRTY=0; QUIET=0; USE_GH=1
for a in "$@"; do
  case "$a" in
    --auto) AUTO=1;; --force-dirty) FORCE_DIRTY=1;; --quiet) QUIET=1;; --no-gh) USE_GH=0;;
    -h|--help) sed -n '2,25p' "$0"; exit 0;;
    *) echo "unknown arg: $a" >&2; exit 2;;
  esac
done

# 메인 레포 루트(워크트리 안에서 실행돼도 공통 .git의 부모로 간다)
COMMON=$(git rev-parse --path-format=absolute --git-common-dir 2>/dev/null) || { echo "git 레포가 아님" >&2; exit 1; }
ROOT=$(dirname "$COMMON")
cd "$ROOT"
BASE=origin/develop
PROTECTED='^(develop|main|staging)$'
TRASH="$COMMON/gc-trash"
NOW=$(date +%Y%m%d-%H%M%S)
INVOKER_CWD=${OLDPWD:-$PWD}

if [ "$AUTO" = 1 ]; then
  git fetch --prune --quiet origin develop 2>/dev/null || true
fi
git rev-parse --verify -q "$BASE" >/dev/null || { echo "$BASE 없음 — fetch 필요" >&2; exit 1; }

command -v gh >/dev/null 2>&1 || USE_GH=0

# 살아있는 프로세스의 cwd 집합(줄바꿈 구분)
LIVE_CWDS=$(lsof -d cwd -Fn 2>/dev/null | sed -n 's/^n//p' | sort -u || true)

is_live() { # $1=worktree path
  local p="$1"
  printf '%s\n' "$LIVE_CWDS" | grep -q "^$p\(/\|$\)" && return 0
  case "$INVOKER_CWD" in "$p"|"$p"/*) return 0;; esac
  return 1
}

merged_via() { # $1=branch → prints 'ancestor'|'pr#N'|'cherry'|''
  local b="$1"
  if git merge-base --is-ancestor "$b" "$BASE" 2>/dev/null; then echo ancestor; return; fi
  if [ "$USE_GH" = 1 ]; then
    local n
    n=$(gh pr list --state merged --head "$b" --json number --jq '.[0].number' 2>/dev/null || true)
    if [ -n "$n" ] && [ "$n" != null ]; then echo "pr#$n"; return; fi
  fi
  # 리베이스·체리픽 머지: 패치 기준 잔여 커밋이 0이면 머지로 본다
  if [ -z "$(git cherry "$BASE" "$b" 2>/dev/null | grep '^+' || true)" ]; then echo cherry; return; fi
  echo ""
}

removed=0; kept=0
report=""
add() { report+="$1"$'\n'; }

# ---------- 1. 워크트리 ----------
while IFS=$'\t' read -r wt br; do
  [ -z "$wt" ] && continue
  [ "$wt" = "$ROOT" ] && continue
  if [ -z "$br" ]; then br="(detached)"; fi
  short=${wt#"$ROOT"/}
  if [ ! -d "$wt" ]; then
    [ "$AUTO" = 1 ] && git worktree prune
    add "PRUNED   $short (디렉토리 없음)"; continue
  fi
  dirty=$(git -C "$wt" status --porcelain 2>/dev/null | wc -l | tr -d ' ')
  live=""; is_live "$wt" && live=live
  mv=""; [ "$br" != "(detached)" ] && mv=$(merged_via "$br")
  ahead=""; [ "$br" != "(detached)" ] && ahead=$(git rev-list --count "$BASE..$br" 2>/dev/null || echo ?)
  if [ -n "$live" ]; then
    kept=$((kept+1))
    add "LIVE     $short [$br] merged=${mv:-no} dirty=$dirty — 세션/셸이 열려 있음, 먼저 닫을 것"
    continue
  fi
  if [ -z "$mv" ]; then
    kept=$((kept+1))
    add "UNMERGED $short [$br] ahead=$ahead dirty=$dirty"
    continue
  fi
  if [ "$dirty" != 0 ] && [ "$FORCE_DIRTY" != 1 ]; then
    kept=$((kept+1))
    add "DIRTY    $short [$br] merged=$mv dirty=$dirty — --force-dirty 로 패치 보존 후 삭제 가능"
    continue
  fi
  if [ "$AUTO" = 1 ]; then
    if [ "$dirty" != 0 ]; then
      mkdir -p "$TRASH"
      safe=$(printf '%s' "$br" | tr '/' '_')
      git -C "$wt" diff > "$TRASH/$safe-$NOW.patch" || true
      git -C "$wt" ls-files --others --exclude-standard -z | (cd "$wt" && tar -czf "$TRASH/$safe-$NOW-untracked.tgz" --null -T - 2>/dev/null) || true
      add "TRASHED  $short 미커밋분 → $TRASH/$safe-$NOW.*"
    fi
    git worktree remove --force "$wt"
    [ "$br" != "(detached)" ] && git branch -D "$br" >/dev/null 2>&1 || true
    removed=$((removed+1))
    add "REMOVED  $short [$br] merged=$mv"
  else
    add "WOULD-RM $short [$br] merged=$mv dirty=$dirty"
  fi
done < <(git worktree list --porcelain | awk '/^worktree /{wt=substr($0,10)} /^branch /{b=substr($0,8); sub("refs/heads/","",b)} /^$/{print wt "\t" b; wt=""; b=""} END{if(wt!="")print wt "\t" b}')

# ---------- 2. 워크트리 없는 로컬 브랜치 ----------
checked_out=$(git worktree list --porcelain | sed -n 's/^branch refs\/heads\///p')
cur=$(git rev-parse --abbrev-ref HEAD)
while read -r b; do
  [ -z "$b" ] && continue
  printf '%s' "$b" | grep -Eq "$PROTECTED" && continue
  [ "$b" = "$cur" ] && continue
  printf '%s\n' "$checked_out" | grep -qx "$b" && continue
  mv=$(merged_via "$b")
  if [ -n "$mv" ]; then
    if [ "$AUTO" = 1 ]; then
      git branch -D "$b" >/dev/null 2>&1 && { removed=$((removed+1)); add "REMOVED  branch $b merged=$mv"; }
    else
      add "WOULD-RM branch $b merged=$mv"
    fi
  else
    ahead=$(git rev-list --count "$BASE..$b" 2>/dev/null || echo ?)
    remote=$(git rev-parse --verify -q "origin/$b" >/dev/null && echo pushed || echo local-only)
    kept=$((kept+1))
    add "UNMERGED branch $b ahead=$ahead $remote"
  fi
done < <(git for-each-ref --format='%(refname:short)' refs/heads/)

# ---------- 3. 완료 상태인데 활성 위치에 남은 문서 ----------
# 상태 헤더의 첫 토큰이 ✅(완료)·🗄(대체)이면 아카이브 대상. "🟢 활성 · ✅ 구현됨"처럼 활성이
# 앞서는 spec은 설계 기록으로 영구 보존 대상이라 제외한다(CLAUDE.md 문서 체계).
docs_stale=0
for f in docs/superpowers/plans/*.md docs/superpowers/specs/*.md; do
  [ -f "$f" ] || continue
  if head -5 "$f" | grep -Eq '상태: *(✅|🗄)'; then
    docs_stale=$((docs_stale+1)); add "DOC      $f — 완료/대체 상태인데 archive/ 밖 (PR로 이동)"
  fi
done
plan_count=$(ls docs/superpowers/plans/*.md 2>/dev/null | wc -l | tr -d ' ')

# ---------- 출력 ----------
if [ "$QUIET" = 1 ] && [ "$removed" = 0 ] && [ "$kept" = 0 ] && [ "$docs_stale" = 0 ]; then exit 0; fi
if [ "$QUIET" = 1 ]; then
  # 훅 모드: 컨텍스트 비용을 아끼려 요약 두 줄만. 상세는 인자 없이 실행하면 표로 나온다.
  live_n=$(printf '%s' "$report" | grep -c '^LIVE ' || true)
  dirty_n=$(printf '%s' "$report" | grep -c '^DIRTY ' || true)
  echo "[repo-gc] 삭제 $removed · 잔여: 세션이 잡은 워크트리 $live_n(세션 닫으면 다음 시작 때 자동 삭제) · 미커밋 잔재 $dirty_n · 미머지 $((kept-live_n-dirty_n)) · 아카이브 대상 문서 $docs_stale · 활성 plan $plan_count"
  printf '%s' "$report" | grep -E '^TRASHED ' || true
  [ "$removed" != 0 ] && printf '%s' "$report" | grep -E '^REMOVED ' | sed 's/^/  /' || true
else
  printf '%s' "$report" | sort -k1,1
  echo "---- 삭제 $removed · 잔여 $kept · 아카이브 대상 문서 $docs_stale · 활성 plan $plan_count $( [ "$AUTO" = 1 ] || echo '(드라이런 — --auto 로 실행)')"
fi
