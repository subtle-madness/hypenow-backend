# P — 뷰티 판정 v3 한국어 필터

- **소속 트랙군**: 상세 분석 작업 트랙 — 구조 설계: [specs/2026-07-12-detail-analysis-design.md](../superpowers/specs/archive/2026-07-12-detail-analysis-design.md) · 데이터 층(A·B1·F·B2·B3) 설계: [specs/2026-07-12-analytics-data-layer-design.md](../superpowers/specs/2026-07-12-analytics-data-layer-design.md)
- **의존**: —
- **상태**: ⚠️ 코드 ✅·**후속 운영 미실행** (07-30 발견: `reset-influencer-judgments-v3.sql`이 운영에서 끝내 실행되지 않아 서빙 `INFLUENCER`의 92.7%가 4분류 시절 판정으로 남았다 — 트랙 CC에서 교정. 교훈: "머지 후 운영 작업"이 남은 트랙을 ✅로 닫으면 누락이 보이지 않는다)

## 내용

`BeautyClass`에 `FOREIGN_INFLUENCER` 신설(5분류, V21) — INFLUENCER를 "한국어 콘텐츠 중심"으로 재정의해 외국인 뷰티 인플루언서를 beauty=false 세그먼트로 분리(COMPANY는 언어 무관). 프롬프트 v3 경계 규칙(캡션 최우선·영어 bio+한국어 캡션→한국어). 하류(analytics·was)는 파생 boolean만 읽어 무변경. 머지 후 일회성 운영: `deploy/scripts/reset-influencer-judgments-v3.sql`(CLAUDE 판정 INFLUENCER만 초기화, MANUAL 보존)→어드민 BEAUTY 재판정 — [specs/2026-07-28-beauty-korean-filter-design.md](../superpowers/specs/archive/2026-07-28-beauty-korean-filter-design.md)
