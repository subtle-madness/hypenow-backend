# D3 — 드로어 as-of

- **소속 트랙군**: 상세 분석 작업 트랙 — 구조 설계: [specs/2026-07-12-detail-analysis-design.md](../superpowers/specs/2026-07-12-detail-analysis-design.md) · 데이터 층(A·B1·F·B2·B3) 설계: [specs/2026-07-12-analytics-data-layer-design.md](../superpowers/specs/2026-07-12-analytics-data-layer-design.md)
- **의존**: D, B1(스냅샷 미러)
- **상태**: 🗑 제거(07-30)

## 내용

`GET /api/posts/{shortCode}?endDate=` — 집계 기간 끝 시점 스냅샷으로 지표 재구성(captured_at ≤ endDate의 KST 하루 끝 중 최신), 스냅샷 없으면 404(그 시점 화면에 부재). 생략 시 최신. **07-30 제거**(D와 함께) — 스냅샷 미러의 유일한 소비처였고, 프론트에 시점 단건 조회 UI가 존재한 적이 없다. as-of 재도입 시 raw 뷰 직접 조회가 전제
