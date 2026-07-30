# N — 프로필 400 → Hiker 폴백

- **소속 트랙군**: 상세 분석 작업 트랙 — 구조 설계: [specs/2026-07-12-detail-analysis-design.md](../superpowers/specs/2026-07-12-detail-analysis-design.md) · 데이터 층(A·B1·F·B2·B3) 설계: [specs/2026-07-12-analytics-data-layer-design.md](../superpowers/specs/2026-07-12-analytics-data-layer-design.md)
- **의존**: —
- **상태**: ✅ (옵션 추가까지 — 실제 전환은 `profile.source` 수동 UPDATE·어드민 UI로 사용자 결정)

## 내용

crawler `ProfileSource.SELF_HIKER_FALLBACK` 신설 — SELF(`web_profile_info`)로 배치 조회하되 **IP 무관 HTTP 400**(비즈니스 카테고리 버그, 07-23 ~29% 확산) 계정만 HikerAPI `/v2/user/by/username`로 2차 조회하는 컴포지트 페처 `SelfWithHikerFallbackProfileFetcher`(라벨 `profile-self-hiker`, crawl_run 1건). 혼합 배치는 `ProfileExtractor.detect` 셰이프 감지로 아이템별 소스를 `raw_profile.source`에 기록(소비처 3곳 — CollectJob·QualifyJob·ProfileSupplementer) + 어드민 소스 라디오 노출 — [specs/2026-07-26-profile-400-hiker-fallback-design.md](../superpowers/specs/2026-07-26-profile-400-hiker-fallback-design.md) [plans/2026-07-26-profile-400-hiker-fallback.md → archive]
