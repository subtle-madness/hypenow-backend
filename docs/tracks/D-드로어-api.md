# D — 드로어 API

- **소속 트랙군**: 상세 분석 작업 트랙 — 구조 설계: [specs/2026-07-12-detail-analysis-design.md](../superpowers/specs/2026-07-12-detail-analysis-design.md) · 데이터 층(A·B1·F·B2·B3) 설계: [specs/2026-07-12-analytics-data-layer-design.md](../superpowers/specs/2026-07-12-analytics-data-layer-design.md)
- **의존**: B1, B2·B3(확장분)
- **상태**: 🗑 제거(07-30)

## 내용

`GET /api/posts/{shortCode}` — post/account/comments + analysis 블록·댓글 aiCategory(B2·B3 산출물 포함, 1회 호출). 댓글 수집 제외(07-14)로 comments·aiCategory는 유입 없음. **07-30 제거** — 소비자 부재(운영 caddy 로그 `/api/*` 0건, celfit-front는 `API_PREFIX="/v1"` 하드코딩으로 `/api` 경로 생성 자체가 불가, `/api/**`는 로그인 월 뒤). 후속은 `/v1/contents/{id}`(+ai-report)가 담당
