# O — timely 캘린더일 정합

- **소속 트랙군**: 상세 분석 작업 트랙 — 구조 설계: [specs/2026-07-12-detail-analysis-design.md](../superpowers/specs/2026-07-12-detail-analysis-design.md) · 데이터 층(A·B1·F·B2·B3) 설계: [specs/2026-07-12-analytics-data-layer-design.md](../superpowers/specs/2026-07-12-analytics-data-layer-design.md)
- **의존**: B1(미러), 04 뷰
- **상태**: 🔨 (PR 리뷰 대기 · 소급 런북은 배포 후 실행)

## 내용

`ContentAnalysisJob` 후보 선정을 raw 후보 뷰(04, 캘린더일 timely) 직접 소비로 교체 — 간격식 판정 이원화 제거(일 수백 건 late_backfill 누수→랭킹 영구 제외 해소). 기존 마킹 양방향 소급 런북 포함 — [specs/2026-07-28-timely-calendar-alignment-design.md](../superpowers/specs/2026-07-28-timely-calendar-alignment-design.md) [plans/2026-07-28-timely-calendar-alignment.md](../superpowers/plans/2026-07-28-timely-calendar-alignment.md)
