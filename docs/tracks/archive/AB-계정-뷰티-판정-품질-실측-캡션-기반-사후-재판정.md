# AB — 계정 뷰티 판정 품질 — 실측 캡션 기반 사후 재판정

- **소속 트랙군**: 상세 분석 작업 트랙 — 구조 설계: [specs/2026-07-12-detail-analysis-design.md](../superpowers/specs/archive/2026-07-12-detail-analysis-design.md) · 데이터 층(A·B1·F·B2·B3) 설계: [specs/2026-07-12-analytics-data-layer-design.md](../superpowers/specs/2026-07-12-analytics-data-layer-design.md)
- **의존**: P
- **상태**: ✅ PR #216 머지(2026-07-30)·운영 반영(PR #247 승격에서 crawler V22 적용 success 확인). 야간 `beauty` 크론에 rejudge=true 배선 완료 — 자동 자기교정이 스케줄로 돈다

## 내용

서빙 중 뷰티 인플루언서 7,095개 중 게시물 뷰티 비율 0%인 886개, 스팟체크 20개 중 17개(85%)가 오판(육아·다이어트·여행·피트니스 계정이 뷰티로 분류)으로 실측 확인. 원인은 프로필 소스가 `HIKER_MOBILE`/`DATALIKERS`면 응답에 게시물이 없어 판정 캡션이 항상 0건이고, 게시물 수집(`findCollectTargets`)이 `beauty=true`만 대상이라 판정 시점에 게시물 근거가 원리적으로 존재할 수 없으며(닭-달걀), 기존 재판정(`findRejudgeTargets`)이 `beauty=false`만 대상이라 뷰티로 잘못 통과한 계정이 영구 고착되던 것. 이미 crawler DB(`raw_media_page.payload`, REELS 잡이 `HIKER_V2_CLIPS`로 적재)에 있는 게시물 캡션 원문을 폴백으로 써(추가 크롤 0) 판정 캡션 소스를 넓히고, `influencer.beauty_caption_count`·`beauty_basis`(CAPTION/BIO/CATEGORY_ONLY, V22) 컬럼으로 판정 근거를 가시화, 캡션 0건으로 판정된 뒤 릴스가 쌓인 계정을 `beauty` 값과 무관하게 재판정하는 두 번째 경로 `findCaptionRejudgeTargets`(릴스 아이템 3건 이상)를 신설. 프롬프트는 인스타그램 자기신고 `category`를 금지 근거가 아니라 우선순위 낮은 근거로 재정의하고 `reason`→`class` 출력 순서 전환, `basis` 자기보고 도입. Gemini `RESPONSE_SCHEMA`가 V21의 5분류(`FOREIGN_INFLUENCER`)를 반영 못 하던 버그도 동봉 픽스. 운영 실측(07-30): V22 백필 대상 19,093건, 현재 조건에 걸리는 재판정 대상 730건, 미판정 QUALIFIED 계정 0건, `beauty.batch-limit` 2000 — [specs/2026-07-30-beauty-judgment-quality-design.md](../superpowers/specs/2026-07-30-beauty-judgment-quality-design.md) [plans/2026-07-30-beauty-judgment-quality.md](../superpowers/plans/archive/2026-07-30-beauty-judgment-quality.md)
