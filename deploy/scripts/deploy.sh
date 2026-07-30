#!/usr/bin/env bash
# 맥에서 실행: jar 빌드 → multi-arch 이미지 push → 서버 pull·재기동
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
# multi-arch 빌더 준비 — 기본 docker 드라이버는 멀티 플랫폼 push 불가 (1회 생성 후 재사용)
docker buildx inspect hypenow-multiarch >/dev/null 2>&1 \
  || docker buildx create --name hypenow-multiarch --driver docker-container
for svc in "${SERVICES[@]}"; do
  REPO="ghcr.io/subtle-madness/hypenow-$svc"
  ./gradlew ":$svc:bootJar"
  docker buildx build --builder hypenow-multiarch --platform linux/arm64,linux/amd64 \
    -t "$REPO:latest" -t "$REPO:$SHA_TAG" --push "$svc"
done
ssh "$HOST" "cd ~/deploy && docker compose pull ${SERVICES[*]} && docker compose up -d && docker compose ps"
echo "배포 완료 — 서비스: ${SERVICES[*]}, 이미지 태그: latest, $SHA_TAG"
# 댕글링 이미지 정리 — CD(cd.yml)와 같은 서버·같은 목적(회수 가능 디스크 회수, dangling-only).
# 이 경로도 같은 서버에 :latest를 덮어써 댕글링을 남기므로 CD와 일관되게 정리한다.
# 배포 자체는 위에서 이미 끝났으므로 실패는 흡수만 하고 스크립트를 실패시키지 않는다.
ssh "$HOST" 'docker image prune -f' || echo "이미지 정리 실패(무시) — 다음 배포에서 재시도됨"
