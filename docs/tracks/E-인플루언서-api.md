# E — 인플루언서 API

- **소속 트랙군**: 상세 분석 작업 트랙 — 구조 설계: [specs/2026-07-12-detail-analysis-design.md](../superpowers/specs/archive/2026-07-12-detail-analysis-design.md) · 데이터 층(A·B1·F·B2·B3) 설계: [specs/2026-07-12-analytics-data-layer-design.md](../superpowers/specs/2026-07-12-analytics-data-layer-design.md)
- **의존**: C1, C2
- **상태**: 🗑 제거(07-27)

## 내용

`GET /api/influencers/{handle}` — profile + report(AccountReport 결정 지표 + C2 카피 7종) 조합 서빙. **07-27 제거** — 소비자 부재(celfit-front는 `/v1` 프록시 전용, `/api/**`는 로그인 월 뒤라 외부 접근 불가) + 카피 7종→5종 개편(V40)으로 구 카피 컬럼이 NULL로 쌓여 점차 빈 카피를 서빙하게 되는 표면이었음. 후속은 `/v1/influencers/{id}`(+ai-report)가 담당
