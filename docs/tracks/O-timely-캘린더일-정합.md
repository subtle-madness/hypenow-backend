# O — timely 캘린더일 정합

- **소속 트랙군**: 상세 분석 작업 트랙 — 구조 설계: [specs/2026-07-12-detail-analysis-design.md](../superpowers/specs/2026-07-12-detail-analysis-design.md) · 데이터 층(A·B1·F·B2·B3) 설계: [specs/2026-07-12-analytics-data-layer-design.md](../superpowers/specs/2026-07-12-analytics-data-layer-design.md)
- **의존**: B1(미러), 04 뷰
- **상태**: ✅ (구현·배포·소급 런북 실행 전부 완료, 2026-07-30)

## 내용

`ContentAnalysisJob` 후보 선정을 raw 후보 뷰(04, 캘린더일 timely) 직접 소비로 교체 — 간격식 판정 이원화 제거(일 수백 건 late_backfill 누수→랭킹 영구 제외 해소). 기존 마킹 양방향 소급 런북 포함 — [specs/2026-07-28-timely-calendar-alignment-design.md](../superpowers/specs/2026-07-28-timely-calendar-alignment-design.md) [plans/archive/2026-07-28-timely-calendar-alignment.md](../superpowers/plans/archive/2026-07-28-timely-calendar-alignment.md)

**07-30 소급 런북 실행 완료(plans 문서 §Task 4)**: `v_contents` 138,755행 추출(timely 10,651 /
비timely 128,104, 소요 8초) → dry-run(a_승격 2,822 / b_강등 4,423 / c_추출누락 6,343 — 미변경 /
랭킹모수 전 6,678) → COMMIT(UPDATE 2,822+4,423, 항등식 mismatch=0 확인 후 커밋) — **랭킹 모수
6,678 → 5,755**로 정직하게 순감소(캘린더일 정의에 맞춘 결과로 수용, DECISIONS.md 07-28 결정
참조). 플랜 문서 상태 헤더를 ✅로 갱신하고 `plans/archive/`로 이동 완료.
