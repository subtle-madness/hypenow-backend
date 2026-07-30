#!/usr/bin/env bash
# was 무중단 롤링 재기동 — 신 컨테이너를 먼저 띄워 healthy·스모크 확인 후 구 컨테이너를 제거한다.
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

# 잔재 검사는 정지분 포함(-a) — 정지된 구 컨테이너가 있으면 --scale이 신 생성 대신 그걸
# 재기동해 "구 이미지가 신으로 판정 → 현행 제거 → CD 초록불인데 구버전 서빙"이 된다
# (리뷰 C1 실측 재현). stop~rm 사이 40초 창에서 잡이 죽으면 실제로 생기는 상태다.
ALL="$(docker compose ps -qa "$SVC")"
OLD="$(docker compose ps -q "$SVC")"
if [ -z "$OLD" ] && [ -z "$ALL" ]; then
  echo "$SVC 컨테이너 없음 — 일반 기동으로 대체"
  docker compose up -d "$SVC"
  exit 0
fi
if [ "$(printf '%s\n' "$ALL" | grep -c .)" -ne 1 ] || [ -z "$OLD" ]; then
  echo "중단: $SVC 컨테이너가 '실행 중 1개'가 아님(정지 잔재 포함 검사) — 정리 후 재시도" >&2
  docker compose ps -a "$SVC" >&2
  exit 1
fi

# 신 컨테이너 추가 기동 — 구 컨테이너는 --no-recreate가 보존(compose 설정이 바뀐 배포에서도),
# 신 쪽만 새 이미지·새 설정으로 뜬다. --no-deps: 의존 서비스 선행은 CD가 보장.
docker compose up -d --no-deps --no-recreate --scale "$SVC=2" "$SVC"
NEW="$(docker compose ps -qa "$SVC" | grep -vx "$OLD" || true)"
if [ -z "$NEW" ] || [ "$(printf '%s\n' "$NEW" | grep -c .)" -ne 1 ]; then
  echo "중단: 신 $SVC 컨테이너 식별 실패 (docker compose ps -a로 상태 확인)" >&2
  exit 1
fi

# 신 컨테이너 = 방금 pull 된 이미지인지 독립 검증 — 잔재 부활 계열(C1)의 근본 방어.
# `config --images <svc>`는 의존 서비스 이미지까지 반환해 head -1이 엉뚱한 서비스를 집는다
# (07-30 첫 실전 롤링이 analytics 이미지를 기준 삼아 오탐 중단) — config JSON에서 서비스명으로 뽑는다.
WANT_IMG="$(docker compose config --format json \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["services"][sys.argv[1]]["image"])' "$SVC")"
WANT_ID="$(docker image inspect -f '{{.Id}}' "$WANT_IMG")"
if [ "$(docker inspect -f '{{.Image}}' "$NEW")" != "$WANT_ID" ]; then
  echo "중단: 신 $SVC 컨테이너가 최신 이미지가 아님(구 잔재 부활 의심) — 신만 제거" >&2
  docker rm -f "$NEW" >/dev/null
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

# 스모크 — TCP 리슨(healthcheck)은 "기동은 됐는데 쿼리가 깨지는" 07-20 계열을 못 잡는다.
# 익명 조회 경로(/v1/stats — analysis DB 실조회)를 신 컨테이너 안에서 직접 확인(이미지에
# curl이 없어 bash /dev/tcp 원시 HTTP). ROLLOUT_SMOKE_PATH="" 로 비활성(테스트용).
SMOKE_PATH="${ROLLOUT_SMOKE_PATH-/v1/stats}"
SMOKE_PORT="${ROLLOUT_SMOKE_PORT:-8081}"
if [ -n "$SMOKE_PATH" ]; then
  if ! docker exec "$NEW" bash -c "exec 3<>/dev/tcp/127.0.0.1/$SMOKE_PORT \
      && printf 'GET $SMOKE_PATH HTTP/1.0\r\nHost: localhost\r\n\r\n' >&3 \
      && head -n1 <&3 | grep -qE '^HTTP/[0-9.]+ 200([^0-9]|\$)'"; then
    echo "실패: 신 $SVC 스모크($SMOKE_PATH) 미통과 — 신만 제거, 구버전이 계속 서빙" >&2
    docker logs --tail 100 "$NEW" >&2 || true
    docker rm -f "$NEW" >/dev/null
    exit 1
  fi
fi

# 구 컨테이너 종료 — SIGTERM에 Spring graceful drain. -t 40은 stop_grace_period 미적용
# 구세대 컨테이너(기본 10s)에도 드레인 여유를 주기 위한 명시값.
docker stop -t 40 "$OLD" >/dev/null
docker rm "$OLD" >/dev/null
echo "롤링 완료: $SVC 신 컨테이너 ${NEW:0:12} 전환, 구 ${OLD:0:12} 제거"
