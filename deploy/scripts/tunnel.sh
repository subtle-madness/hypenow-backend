#!/usr/bin/env bash
# 오라클 analysis DB로 SSH 터널 — analytics cloud 프로파일·psql용 (localhost:15432)
# 사용법: deploy/scripts/tunnel.sh <ssh-host>
set -euo pipefail
HOST="${1:?사용법: tunnel.sh <ssh-host>}"
echo "localhost:15432 → $HOST 의 postgres(루프백). 종료: Ctrl-C"
ssh -N -L 15432:127.0.0.1:5432 "$HOST"
