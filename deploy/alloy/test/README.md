# Alloy 로그 파이프라인 검증 리그

`deploy/alloy/config.alloy`를 **수정 없이** 로컬에서 태워보는 임시 스택이다. compose 서비스명
(`was`·`monitoring`·`caddy` — service 라벨의 출처)과 네트워크 이름(`deploy_prod`)을 운영과
똑같이 맞춰, relabel 규칙과 multiline 병합·JVM 분기가 실제로 걸리는지 확인한다.

> ⚠️ **로컬 맥 전용. 서버에서 실행 금지** — 운영과 같은 이름 공간을 쓴다.

## 기동·정리

```bash
docker compose -p alloytest -f deploy/alloy/test/compose.test.yaml up -d    # 기동
docker compose -p alloytest -f deploy/alloy/test/compose.test.yaml down -v  # 정리(볼륨까지)
```

기동 후 40초쯤 지나야 첫 로그가 Loki에 들어온다(alloy `refresh_interval` 30s + 발생기 주기 10s).

- Loki API: http://localhost:3100
- Grafana: http://localhost:3000 (익명 Admin, 프로비저닝된 대시보드가 그대로 뜬다)

## 검증 3종

| 확인 | 쿼리 | 기대 |
|---|---|---|
| `level` 라벨 | `/loki/api/v1/label/level/values` | `["ERROR","INFO","WARN"]` — JVM 파이프라인에서만 붙는다 |
| 스택트레이스 병합 | `{service="was", level="ERROR"}` | 엔트리 1건이 6줄(`IllegalStateException`~`... 3 more`)을 통째로 담는다 |
| 비-JVM 회귀 | `{service="caddy"}` | 전 엔트리가 1줄·`level` 라벨 없음. 2줄 이상이면 분기가 잘못 걸린 것 |

`service` 라벨은 JVM 여부와 무관하게 **모든** 수집 컨테이너에 붙는다(compose 서비스명) —
리그에선 `was`·`monitoring`·`caddy` 외에 리그 자신(`loki`·`alloy`·`grafana`)도 보인다.

## 로그 발생기

`fixtures/emit-*.sh`는 2026-08-12 운영 서버에서 실측한 로그 포맷을 그대로 재현한다. 포맷이
바뀌면(로깅 설정 변경 등) 여기 픽스처부터 갱신해야 검증이 의미를 유지한다.
