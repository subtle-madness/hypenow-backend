#!/usr/bin/env bash
# was 무중단 롤링 재기동 — 신 컨테이너를 먼저 띄워 healthy 확인 후 구 컨테이너를 제거한다.
# CD(cd.yml)가 pull·analytics healthy 확인 뒤 서버에서 호출. 전제 3가지:
#   ① 대상 서비스는 host 포트 미점유(caddy가 서비스명 도커 DNS로 프록시 — 복제 2개 공존 가능)
#   ② compose에 healthcheck 정의   ③ 평상시 복제 1
# 실패 시 신 컨테이너만 제거하고 구가 계속 서빙한다(무중단 실패 — CD만 빨간불).
# 교대 순간의 dial 실패는 Caddyfile의 lb_try_duration 재시도가 흡수한다.
# 주의: 운영 파일 단독 경로라 orphan 경고(test-*)가 뜬다 — --remove-orphans 금지(README §12).
# 사용법: rollout.sh [서비스]   (기본 was)
set -euo pipefail
cd "$(cd "$(dirname "$0")" && pwd)/.."
SVC="${1:-was}"

OLD="$(docker compose ps -q "$SVC")"
if [ -z "$OLD" ]; then
  echo "$SVC 실행 중 컨테이너 없음 — 일반 기동으로 대체"
  docker compose up -d "$SVC"
  exit 0
fi
if [ "$(printf '%s\n' "$OLD" | wc -l)" -ne 1 ]; then
  echo "중단: $SVC 컨테이너가 2개 이상 — 이전 롤링 잔재를 정리한 뒤 재시도 (docker compose ps)" >&2
  exit 1
fi

# 신 컨테이너 추가 기동 — 구 컨테이너는 --no-recreate가 보존(compose 설정이 바뀐 배포에서도),
# 신 쪽만 새 이미지·새 설정으로 뜬다. --no-deps: 의존 서비스 선행은 CD가 보장.
docker compose up -d --no-deps --no-recreate --scale "$SVC=2" "$SVC"
NEW="$(docker compose ps -q "$SVC" | grep -vx "$OLD" || true)"
if [ -z "$NEW" ]; then
  echo "중단: 신 $SVC 컨테이너 식별 실패 (docker compose ps로 상태 확인)" >&2
  exit 1
fi

# healthy 대기 — 기동(2g pre-touch + Flyway) 여유 기본 36회×5초=180초 (테스트만 env 축소)
status=unknown
for _ in $(seq 1 "${ROLLOUT_WAIT_TRIES:-36}"); do
  status="$(docker inspect -f '{{.State.Health.Status}}' "$NEW" 2>/dev/null || echo unknown)"
  [ "$status" = "healthy" ] && break
  sleep 5
done
if [ "$status" != "healthy" ]; then
  echo "실패: 신 $SVC 컨테이너 healthy 미도달(마지막 상태: $status) — 신만 제거, 구버전이 계속 서빙" >&2
  docker logs --tail 100 "$NEW" >&2 || true
  docker rm -f "$NEW" >/dev/null
  exit 1
fi

# 구 컨테이너 종료 — SIGTERM에 Spring graceful drain. -t 40은 stop_grace_period 미적용
# 구세대 컨테이너(기본 10s)에도 드레인 여유를 주기 위한 명시값.
docker stop -t 40 "$OLD" >/dev/null
docker rm "$OLD" >/dev/null
echo "롤링 완료: $SVC 신 컨테이너 ${NEW:0:12} 전환, 구 ${OLD:0:12} 제거"
