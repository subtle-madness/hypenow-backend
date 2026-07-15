#!/usr/bin/env bash
# 맥에서 실행: 서버의 최신 덤프를 로컬로 — 오라클 계정이 사라져도 사본은 손안에
# 사용법: deploy/scripts/pull-backup.sh <ssh-host>
set -euo pipefail
HOST="${1:?사용법: pull-backup.sh <ssh-host>}"
DEST="$HOME/backups/hypenow"
mkdir -p "$DEST"
LATEST="$(ssh "$HOST" 'ls -1t ~/backups/analysis-*.sql.gz | head -1')"
scp "$HOST:$LATEST" "$DEST/"
echo "가져옴: $DEST/$(basename "$LATEST")"
