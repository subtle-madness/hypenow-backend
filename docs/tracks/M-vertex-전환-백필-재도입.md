# M — Vertex 전환 + 백필 재도입

- **소속 트랙군**: 상세 분석 작업 트랙 — 구조 설계: [specs/2026-07-12-detail-analysis-design.md](../superpowers/specs/archive/2026-07-12-detail-analysis-design.md) · 데이터 층(A·B1·F·B2·B3) 설계: [specs/2026-07-12-analytics-data-layer-design.md](../superpowers/specs/2026-07-12-analytics-data-layer-design.md)
- **의존**: L
- **상태**: ✅ (구현 완료 — GCP 준비·실 스모크·본 백필은 런북 절차로 사용자 진행 대기)

## 내용

일상 경로를 AI Studio 무료 키 → Vertex AI(SA OAuth)로 완전 전환(`analytics.llm-provider=vertex`, `VertexTokenProvider`+`VertexHttpApi`), 배치도 GCS 경유로 Vertex 전환(상관관계는 에코 파싱으로 재설계). crawler 뷰티 판정은 무접촉(뷰티 판정 v2에서 claude-api 구독으로 별도 전환). 04 뷰·`ContentAnalysisJob` 자격에 "최근 N개 윈도우 포함" OR 추가로 07-19 백필 MVP 제외를 번복, `metric_timeliness`를 timely/late_backfill로 직접 분기. (07-23 개정: `ContentAnalysisJob`의 OR 결합 단일 쿼리를 `run()`/`runLateBackfill()` 두 진입점으로 분리 — 예산 공유 문제 해소, LIMIT 완전 제거·실질 상한은 LLM 쿼타로 대체. [specs/2026-07-23-content-analysis-timely-backfill-split-design.md](../superpowers/specs/archive/2026-07-23-content-analysis-timely-backfill-split-design.md). 같은 날 후속: LIMIT 폐지로 드러난 순차 처리 병목을 `runQuery()` 동시 처리(병렬)로 해소 — `analytics.analyze-concurrency`(기본 8) 신설. [specs/2026-07-23-content-analysis-concurrency-design.md](../superpowers/specs/archive/2026-07-23-content-analysis-concurrency-design.md)) 사용자 런북: [runbooks/2026-07-20-vertex-backfill-runbook.md](../runbooks/2026-07-20-vertex-backfill-runbook.md)
