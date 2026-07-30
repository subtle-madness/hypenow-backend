# Q — 인플루언서 리포트 개편(백엔드)

- **소속 트랙군**: 상세 분석 작업 트랙 — 구조 설계: [specs/2026-07-12-detail-analysis-design.md](../superpowers/specs/2026-07-12-detail-analysis-design.md) · 데이터 층(A·B1·F·B2·B3) 설계: [specs/2026-07-12-analytics-data-layer-design.md](../superpowers/specs/2026-07-12-analytics-data-layer-design.md)
- **의존**: C1, C2, B4
- **상태**: 🔨 (PR 리뷰 대기)

## 내용

피어 퍼센타일·중앙값 ER 파생 뷰(V39 `account_peer_stats` — 주 카테고리×팔로워 버킷) + `account_analyses` 요약 3분할(V40, perf/content/ad_summary) + 계정 카피 7종→5종(tagline 상세화, ad_headline·성장세·유효 팔로워·유사도는 LLM 제거하고 was 알고리즘 산출로 전환) + was 리포트 DTO v2(전체/광고 2행 스탯·성장세·상위%, 유효 팔로워, 미리보기 bars에 캡션·썸네일·브랜드) + 신규 `GET /v1/brands/{brand}/influencers`·`GET /v1/influencers/{id}/similar`(traits Jaccard). **07-28 프론트 확정 스펙 정렬(6.22 리포트 v2·6.23 유사 카드·6.24 이메일 중복 확인) 포함**: v1(6.5)은 프론트 라이브 소비 중이라 원형 보존하고 v2를 `/v2` 병행 신설, 브랜드 hover 엔드포인트는 6.22 `ads.brands` 인라인으로 흡수·삭제, 유효 팔로워는 발굴 목록(6.21)과 산식 단일화(`EffectiveFollowers` 유틸) — [plans/2026-07-27-influencer-report-redesign-backend.md](../superpowers/plans/archive/2026-07-27-influencer-report-redesign-backend.md) [plans/2026-07-28-influencer-report-v2-spec-alignment.md](../superpowers/plans/archive/2026-07-28-influencer-report-v2-spec-alignment.md)
