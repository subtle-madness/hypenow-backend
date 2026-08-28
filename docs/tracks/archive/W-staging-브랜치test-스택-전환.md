# W — staging 브랜치·test 스택 전환

- **소속 트랙군**: 상세 분석 작업 트랙 — 구조 설계: [specs/2026-07-12-detail-analysis-design.md](../superpowers/specs/archive/2026-07-12-detail-analysis-design.md) · 데이터 층(A·B1·F·B2·B3) 설계: [specs/2026-07-12-analytics-data-layer-design.md](../superpowers/specs/2026-07-12-analytics-data-layer-design.md)
- **의존**: K
- **상태**: ✅ (07-29 전환 완료 — 운영 CD·staging 개통·cd-test 첫 배포 success, 격리 검증: test→운영 DNS 해석부터 차단·구 dev-* 컨테이너 제거 확인)

## 내용

승격 흐름을 develop→**staging**→main으로 개편(develop 머지는 CI만, **develop→staging 머지 = test 배포**, staging→main = 운영 배포 — 검증된 커밋만 운영 승격 보장) + dev 계열 명칭 test 통일(`test-was`·`test-analytics`·`test-postgres`·`test-redis`, `compose.test.yaml`, `--profile test`, `cd-test.yml`, `caddy.d/test-api.caddy`, 이미지 `:staging`·`staging-sha-*`) + **도커 브리지 prod/test 분리**(compose.yaml 선언, test→운영 경로 커널 차단 — 공유 접점은 caddy·postgres-raw 양쪽 소속 둘뿐). 유지(의도적 예외): 도메인 dev-api.hypenow.io·`DEV_*` env·`analytics_dev` 계정/스키마·볼륨 `dev-pg-data`. monitoring 배선 시 이름 매핑: dev-monitoring→test-monitoring, `:develop`→`:staging` (07-29 monitoring 배선에 적용 완료) — [specs/2026-07-29-staging-branch-test-stack-design.md](../superpowers/specs/archive/2026-07-29-staging-branch-test-stack-design.md)
