# K — dev 스테이징 환경

- **소속 트랙군**: 상세 분석 작업 트랙 — 구조 설계: [specs/2026-07-12-detail-analysis-design.md](../superpowers/specs/2026-07-12-detail-analysis-design.md) · 데이터 층(A·B1·F·B2·B3) 설계: [specs/2026-07-12-analytics-data-layer-design.md](../superpowers/specs/2026-07-12-analytics-data-layer-design.md)
- **의존**: 기존 CD
- **상태**: ✅ (07-28 개통·E2E 검증 완료 — 가입~로그인~`/v1/stats`·인플루언서 상세 실데이터 응답 확인. **07-29 트랙 S로 개편**: staging 브랜치 트리거·test-* 리네임·네트워크 분리)

## 내용

develop 검증용 dev 스택(dev-was·dev-analytics·dev-postgres)을 운영 인스턴스 동거(`compose.dev.yaml` 분리 + `profiles: ["dev"]` — dev CD가 dev 파일만 서버 동기화, 운영 서비스 정의는 main 배포 전용) + raw는 운영 postgres-raw 공유(`analytics_dev` 스키마 격리, dev 계정 raw 읽기 전용 fail-closed) + 조회 SQL 무접두어화 + raw DataSource `connection-init-sql` search_path(`analytics.raw-schema`, dev만 env 오버라이드) + develop CI 성공 자동 `cd-dev.yml`(뷰 치환 적용·잔존 참조 검증) + `dev-api.hypenow.io`(dev 라우팅은 `caddy.d/dev-api.caddy` 분리 — 운영 Caddyfile은 main 배포로만) — [specs/2026-07-26-dev-staging-environment-design.md](../superpowers/specs/2026-07-26-dev-staging-environment-design.md)
