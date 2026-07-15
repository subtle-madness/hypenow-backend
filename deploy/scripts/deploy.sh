#!/usr/bin/env bash
# 맥에서 실행: jar 빌드 → multi-arch 이미지 push → 서버 pull·재기동
# 사용법: deploy/scripts/deploy.sh <ssh-host>   (예: ubuntu@api.hypenow.io)
set -euo pipefail
HOST="${1:?사용법: deploy.sh <ssh-host>}"
IMAGE=ghcr.io/subtle-madness/hypenow-was:latest
cd "$(git rev-parse --show-toplevel)"
./gradlew :was:bootJar
# multi-arch 빌더 준비 — 기본 docker 드라이버는 멀티 플랫폼 push 불가 (1회 생성 후 재사용)
docker buildx inspect hypenow-multiarch >/dev/null 2>&1 \
  || docker buildx create --name hypenow-multiarch --driver docker-container
docker buildx build --builder hypenow-multiarch --platform linux/arm64,linux/amd64 -t "$IMAGE" --push was
ssh "$HOST" 'cd ~/deploy && docker compose pull was && docker compose up -d && docker compose ps'
