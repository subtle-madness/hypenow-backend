# N — 프로필 400 → Hiker 폴백

- **소속 트랙군**: 상세 분석 작업 트랙 — 구조 설계: [specs/2026-07-12-detail-analysis-design.md](../superpowers/specs/archive/2026-07-12-detail-analysis-design.md) · 데이터 층(A·B1·F·B2·B3) 설계: [specs/2026-07-12-analytics-data-layer-design.md](../superpowers/specs/2026-07-12-analytics-data-layer-design.md)
- **의존**: —
- **상태**: ✅ (옵션 추가까지 — 실제 전환은 `profile.source` 수동 UPDATE·어드민 UI로 사용자 결정)

## 내용

crawler `ProfileSource.SELF_HIKER_FALLBACK` 신설 — SELF(`web_profile_info`)로 배치 조회하되 **IP 무관 HTTP 400**(비즈니스 카테고리 버그, 07-23 ~29% 확산) 계정만 HikerAPI `/v2/user/by/username`로 2차 조회하는 컴포지트 페처 `SelfWithHikerFallbackProfileFetcher`(라벨 `profile-self-hiker`, crawl_run 1건). 혼합 배치는 `ProfileExtractor.detect` 셰이프 감지로 아이템별 소스를 `raw_profile.source`에 기록(소비처 3곳 — CollectJob·QualifyJob·ProfileSupplementer) + 어드민 소스 라디오 노출 — [specs/2026-07-26-profile-400-hiker-fallback-design.md](../superpowers/specs/2026-07-26-profile-400-hiker-fallback-design.md) [plans/2026-07-26-profile-400-hiker-fallback.md → archive]

**08-18 트리거 확장 — 400 또는 연속 빈 응답**: IG가 일부 계정에 익명 API에서 200 + 빈 응답(user 없음)을 주는 케이스(실측 서빙 풀 11계정 회수 가능)를 커버하기 위해 폴백 트리거를 확장. 빈 응답 트랙은 **COLLECT 잡 전용**이며(qualify는 대상 재선정에 종결 장치가 없어 무한 재과금 — 빈 응답은 기존대로 무료 스킵, 400 폴백은 두 잡 공통), 계정별 **연속 2회**(`EMPTY_STREAK_FALLBACK_THRESHOLD`, 인메모리 카운터 — 재기동 시 리셋) 도달 시에만 유료 폴백. 폴백 성공 → 카운터 유지(다음 빈 응답부터 즉시 폴백), 폴백도 **응답으로 확인된** 빈 응답 → 카운터 리셋·재시도 복귀, 폴백 요청 실패(5xx·타임아웃) → 카운터 유지·다음 방문 폴백 재시도(인프라 오류를 소멸 확인으로 오판 방지).

**08-18 빈 응답 30일 수명 정책**: 자체·Hiker 양쪽 모두 빈 응답으로 확인된 계정(`ApifyResult.confirmedEmpty` 채널 — Hiker가 응답으로 확인한 경우만)이 `last_profiled_at`으로부터 30일(`CollectJob.EMPTY_PROFILE_DELETE_AFTER`) 경과 시 404와 동일하게 DELETED 소프트 삭제. `last_profiled_at` null이면 기준점 없음으로 재시도 유지. 마이그레이션 없음. 상세·근거는 DECISIONS.md 08-18 두 행 참조.
