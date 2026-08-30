#!/usr/bin/env bash
# 맥에서 실행: 서버의 최신 덤프를 로컬로 — 오라클 계정이 사라져도 사본은 손안에
# 사용법: deploy/scripts/pull-backup.sh <ssh-host>
#
# ⚠ 세 계열 모두 B2 직스트리밍으로 바뀌어(crawler 08-25, analysis 08-30) 백업이 성공한 날은
#   서버 ~/backups/에 남는 게 없다 — 이 스크립트는 이제 **B2 장애일 폴백 회수용**이다
#   ("덤프 없음 — 건너뜀" 경고가 뜨는 게 정상 상태). 평상시 손안의 사본이 필요하면 B2에서
#   직접 받을 것: `rclone copy b2:hypenow-backups/analysis/ ~/backups/hypenow/ --max-age 2d`
set -euo pipefail
HOST="${1:?사용법: pull-backup.sh <ssh-host>}"
DEST="$HOME/backups/hypenow"
mkdir -p "$DEST"
for prefix in analysis crawler; do
  LATEST="$(ssh "$HOST" "ls -1t ~/backups/$prefix-[0-9]*.sql.* 2>/dev/null | head -1")"
  if [ -z "$LATEST" ]; then echo "경고: $prefix 덤프 없음 — 건너뜀" >&2; continue; fi
  scp "$HOST:$LATEST" "$DEST/"
  echo "가져옴: $DEST/$(basename "$LATEST")"
done
