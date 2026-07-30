# B1 — 드로어 비LLM 집계

- **소속 트랙군**: 상세 분석 작업 트랙 — 구조 설계: [specs/2026-07-12-detail-analysis-design.md](../superpowers/specs/2026-07-12-detail-analysis-design.md) · 데이터 층(A·B1·F·B2·B3) 설계: [specs/2026-07-12-analytics-data-layer-design.md](../superpowers/specs/2026-07-12-analytics-data-layer-design.md)
- **의존**: A
- **상태**: ✅ (스냅샷 미러 🗑 07-30)

## 내용

서빙 뷰·미러 4종 (accounts·contents·content_comments + 지표 스냅샷 이력 `content_metric_snapshots` — 07-13 개통). **07-30 스냅샷 미러만 중단** — 유일 소비처였던 D3·H 제거로 소비자 부재, 미러 12분 30초 중 6~7분을 차지. 뷰(`v_content_metric_snapshots`)는 raw에 존속(이력 조회·향후 추이 그래프 재료), analysis 테이블은 TRUNCATE 후 다음 릴리스 DROP 예정
