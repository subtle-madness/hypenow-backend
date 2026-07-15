#!/usr/bin/env bash
# 맥에서 실행: jar 빌드 → multi-arch 이미지 push → 서버 pull·재기동
# 사용법: deploy/scripts/deploy.sh <ssh-host>   (예: ubuntu@api.hypenow.io)
set -euo pipefail
HOST="${1:?사용법: deploy.sh <ssh-host>}"
IMAGE=ghcr.io/subtle-madness/hypenow-was:latest
cd "$(git rev-parse --show-toplevel)"
./gradlew :was:bootJar
docker buildx build --platform linux/arm64,linux/amd64 -t "$IMAGE" --push was
ssh "$HOST" 'cd ~/deploy && docker compose pull was && docker compose up -d && docker compose ps'
