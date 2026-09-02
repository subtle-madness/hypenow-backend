# H — 랭킹 목록 API

- **소속 트랙군**: 상세 분석 작업 트랙 — 구조 설계: [specs/2026-07-12-detail-analysis-design.md](../superpowers/specs/archive/2026-07-12-detail-analysis-design.md) · 데이터 층(A·B1·F·B2·B3) 설계: [specs/2026-07-12-analytics-data-layer-design.md](../superpowers/specs/2026-07-12-analytics-data-layer-design.md)
- **의존**: D3(as-of 규칙 공유), B3(카테고리·광고·유통사 어휘)
- **상태**: 🗑 제거(07-30)

## 내용

`GET /api/contents` — 프론트 URL 파라미터 계약(start_date·end_date·main/mid/sub_category·content_type·follower·ad_type·distributor·sort·q) 그대로. 기간=게시일 필터, 지표=end_date 시점 스냅샷, 분석 완료 콘텐츠만, 기본 정렬 hype. 유통사 필터는 컬럼 신설(VLM 개통) 전까지 매칭 0. **07-30 제거**(D·D3와 함께 — 07-21 결정이 예고한 "정리 대기" 해소). 랭킹 정본은 `/v1/contents`
