# CC — 뷰티 FOREIGN_INFLUENCER 재판정 확대(프롬프트 v4)

- **소속 트랙군**: 상세 분석 작업 트랙 — 구조 설계: [specs/2026-07-12-detail-analysis-design.md](../superpowers/specs/2026-07-12-detail-analysis-design.md) · 데이터 층(A·B1·F·B2·B3) 설계: [specs/2026-07-12-analytics-data-layer-design.md](../superpowers/specs/2026-07-12-analytics-data-layer-design.md)
- **의존**: P
- **상태**: 🔨 PR #210 머지(2026-07-30) — 재판정 슬라이스 실행 대기(crawler 트랙: 실행 여부는 담당 팀원 확인 사항)

## 내용

운영 실측: `INFLUENCER` 7,128건 중 92.7%(6,605건)가 v3(트랙 P, `FOREIGN_INFLUENCER` 도입) 컷오버(07-28 05:00 UTC) **이전** 판정 — `reset-influencer-judgments-v3.sql`이 운영 미실행된 채 남아 4분류 시절 산출물이 그대로 서빙 중이었고, `findRejudgeTargets`가 `beauty=false`만 대상이라 INFLUENCER는 자가 치유 불가. 게다가 v3 프롬프트 자체도 결함 — "한국어 콘텐츠 중심"을 LLM이 "한국 관련 콘텐츠"로 오독해 한국 화장품을 외국어로 리뷰하는 계정을 INFLUENCER로 오분류(post-v3 표본 33/33 일본 계정). 판정 기준을 **서술 언어 대 다루는 제품·주제의 국적** 축으로 명문화하는 프롬프트 v4로 교정 + `reset-influencer-judgments-v4.sql`(배치 한도 슬라이스 반복, 오래된 판정부터 소진) — [plans/2026-07-30-beauty-foreign-influencer-rejudge.md](../superpowers/plans/2026-07-30-beauty-foreign-influencer-rejudge.md)
