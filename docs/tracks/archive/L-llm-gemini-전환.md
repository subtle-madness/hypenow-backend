# L — LLM Gemini 전환

- **소속 트랙군**: 상세 분석 작업 트랙 — 구조 설계: [specs/2026-07-12-detail-analysis-design.md](../superpowers/specs/archive/2026-07-12-detail-analysis-design.md) · 데이터 층(A·B1·F·B2·B3) 설계: [specs/2026-07-12-analytics-data-layer-design.md](../superpowers/specs/2026-07-12-analytics-data-layer-design.md)
- **의존**: F, B4, C2
- **상태**: ✅ (백필 경로는 M에서 Vertex로 대체)

## 내용

전 분석 축(판정·속성+종합 통합 1콜·카피)을 `gemini-3.1-flash-lite`로 — 프로바이더 선택 `analytics.llm-provider`(기본 gemini, anthropic 롤백), 무료 키 페이싱(15RPM, 일 예산은 batch-limit) + 한도 소진 시 배치 이월, 문구 절제 규칙(LlmGuard). 크롤러 판정은 `crawler.beauty.judge`(기본 claude-api, gemini는 롤백, 팀 프롬프트·파서 재사용). 백필은 유료 키 Batch one-shot(submit/collect) — [plans/archive/2026-07-18-gemini-llm-stack.md](../superpowers/plans/archive/2026-07-18-gemini-llm-stack.md)
