# I — analytics 어드민

- **소속 트랙군**: 상세 분석 작업 트랙 — 구조 설계: [specs/2026-07-12-detail-analysis-design.md](../superpowers/specs/archive/2026-07-12-detail-analysis-design.md) · 데이터 층(A·B1·F·B2·B3) 설계: [specs/2026-07-12-analytics-data-layer-design.md](../superpowers/specs/2026-07-12-analytics-data-layer-design.md)
- **의존**: A
- **상태**: ✅

## 내용

상주 서버(8082) `/ui` — 07-19 파이프라인 관측 대시보드 재설계([specs/2026-07-19-analytics-dashboard-design.md](../superpowers/specs/2026-07-19-analytics-dashboard-design.md)) → **07-21 v3 모델 재설계**: 퍼널 폐기 → 계정 보드·콘텐츠 보드 2축(모수=현재 raw 스냅샷), 크로스 DB 잔여 대조(G1 — 후보 ∩ 미분석, 트랙별 4분할)·커버리지 현 서빙 모수 재정의(G2), 누적 분석 수는 각주 강등 — [plans/2026-07-21-analytics-dashboard-v3-data-model.md](../superpowers/plans/archive/2026-07-21-analytics-dashboard-v3-data-model.md). 잡 카드·실행 피드·폴링·집계 3상태는 v2 유지
