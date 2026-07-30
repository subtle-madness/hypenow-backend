# X — was 무중단 롤링 배포

- **소속 트랙군**: 상세 분석 작업 트랙 — 구조 설계: [specs/2026-07-12-detail-analysis-design.md](../superpowers/specs/2026-07-12-detail-analysis-design.md) · 데이터 층(A·B1·F·B2·B3) 설계: [specs/2026-07-12-analytics-data-layer-design.md](../superpowers/specs/2026-07-12-analytics-data-layer-design.md)
- **의존**: W
- **상태**: ✅ (07-30 첫 실전 롤링 성공 — 운영 무중단 실측 547/548, 유일 순단 1초는 caddy 재생성. 첫 시도의 이미지 검증 오탐은 PR #186으로 수정)

## 내용

운영 CD의 was 재기동을 롤링으로 전환(신 컨테이너 healthy·스모크(`/v1/stats`) 확인 후 구 제거 — `deploy/scripts/rollout.sh`, 잔재·이미지 검증 포함, 실패 시 구가 계속 서빙하는 무중단 실패) + was healthcheck·`stop_grace_period`·`server.shutdown: graceful` + Caddy `lb_try_duration` 재시도 + CD에 caddy reload 스텝(운영 Caddyfile 변경 미반영 갭 해소, 롤링보다 선행) + **expand-contract CI 가드**(`migration-guard` 잡 — analysis DB 마이그레이션의 파괴적 DDL PR 차단, `-- allow-destructive:` 해치. crawler(raw)는 대상 외. **v2**: DROP COLUMN ↔ 보정 UPDATE 짝 검사 — 컬럼 이행의 롤링 창 유실분 최종 백필을 contract 시점에 기계 강제, 예외는 `-- no-backfill:` 태그). analytics·crawler·monitoring·test 스택은 재기동 유지 — [deploy/README.md §5-1](../../deploy/README.md)
