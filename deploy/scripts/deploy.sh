#!/usr/bin/env bash
# 맥에서 실행: jar 빌드 → arm64 이미지 push(서버가 오라클 A1 arm64 단일) → 서버 pull·재기동
# CD 도입(07-20) 후 평상시 배포는 develop→main 머지 — 이 스크립트는 긴급 경로 전용.
# 사용법: deploy/scripts/deploy.sh [--force] <ssh-host> [서비스…]
#   예: deploy.sh ubuntu@api.hypenow.io            (was+analytics 전부)
#       deploy.sh hypenow analytics                (analytics만)
set -euo pipefail
FORCE=0
if [ "${1:-}" = "--force" ]; then FORCE=1; shift; fi
HOST="${1:?사용법: deploy.sh [--force] <ssh-host> [was|analytics …]}"
shift
# 가드: :latest는 마지막 push가 이긴다 — main이 아닌 커밋의 수동 배포가 CD 배포를 덮으면
# 서비스 간 버전 불일치가 난다(07-20 장애: develop was + main analytics → 랭킹 500).
git fetch -q origin main
if [ "$(git rev-parse HEAD)" != "$(git rev-parse origin/main)" ] && [ "$FORCE" -ne 1 ]; then
  echo "거부: 현재 HEAD가 origin/main이 아닙니다. 배포는 develop→main 머지(CD)로 하세요." >&2
  echo "CD 불능 등 긴급 시에만: deploy.sh --force <ssh-host> …" >&2
  exit 1
fi
SERVICES=("$@")
if [ ${#SERVICES[@]} -eq 0 ]; then SERVICES=(was analytics); fi
cd "$(git rev-parse --show-toplevel)"
SHA_TAG="sha-$(git rev-parse --short HEAD)"   # 버전 태그 — 롤백용 (절차: deploy/README.md §5)
# 서버(오라클 A1)는 arm64 단일 — amd64 소비처 없음. 맥이 arm64면 네이티브 빌드, buildx도 arm64 단일이면
# 기본 docker 드라이버로 충분하지만 크로스(인텔 맥 등) 대비 docker-container 드라이버를 그대로 유지.
docker buildx inspect hypenow-multiarch >/dev/null 2>&1 \
  || docker buildx create --name hypenow-multiarch --driver docker-container
for svc in "${SERVICES[@]}"; do
  REPO="ghcr.io/subtle-madness/hypenow-$svc"
  ./gradlew ":$svc:bootJar"
  # 레이어 추출(Spring Boot layered jar, CD와 동일 관용구) — 목적지가 비어있지 않으면 실패하므로 rm -rf 선행
  JAR=$(find "$svc/build/libs" -name '*.jar' ! -name '*-plain.jar')
  COUNT=$(echo "$JAR" | grep -c . || true)
  if [ "$COUNT" -ne 1 ]; then
    echo "bootJar 후보가 1개가 아님(plain jar 제외 후 ${COUNT}개) — 스테일 빌드 산출물 의심: $JAR" >&2
    exit 1
  fi
  rm -rf "$svc/build/extracted"
  java -Djarmode=tools -jar "$JAR" extract --layers --launcher --destination "$svc/build/extracted"
  docker buildx build --builder hypenow-multiarch --platform linux/arm64 \
    -t "$REPO:latest" -t "$REPO:$SHA_TAG" --push "$svc"
done
ssh "$HOST" "cd ~/deploy && docker compose pull ${SERVICES[*]} && docker compose up -d && docker compose ps"
echo "배포 완료 — 서비스: ${SERVICES[*]}, 이미지 태그: latest, $SHA_TAG"
