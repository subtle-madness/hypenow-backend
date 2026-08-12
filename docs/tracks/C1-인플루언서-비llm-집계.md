# C1 — 인플루언서 비LLM 집계

- **소속 트랙군**: 상세 분석 작업 트랙 — 구조 설계: [specs/2026-07-12-detail-analysis-design.md](../superpowers/specs/archive/2026-07-12-detail-analysis-design.md) · 데이터 층(A·B1·F·B2·B3) 설계: [specs/2026-07-12-analytics-data-layer-design.md](../superpowers/specs/2026-07-12-analytics-data-layer-design.md)
- **의존**: A
- **상태**: ✅

## 내용

AccountReport 결정 지표 — 계정 요약·게시물 시계열 2종 뷰 + 미러. 카테고리 믹스는 07-21에 analysis DB 파생 뷰(V35)로 이관 — 소스인 캡션 분류가 analysis DB라 raw 뷰로는 만들 수 없다
