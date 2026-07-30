# T — trait 어휘 통제(유사도 v2 2단계)

- **소속 트랙군**: 상세 분석 작업 트랙 — 구조 설계: [specs/2026-07-12-detail-analysis-design.md](../superpowers/specs/2026-07-12-detail-analysis-design.md) · 데이터 층(A·B1·F·B2·B3) 설계: [specs/2026-07-12-analytics-data-layer-design.md](../superpowers/specs/2026-07-12-analytics-data-layer-design.md)
- **의존**: R
- **상태**: 🔨 (구현 완료 — PR·배포·매핑 잡 실행 대기)

## 내용

07-29 리포트 개편 카피 백필 완결로 traits 전량이 새 프롬프트 산출로 교체됐는데도 난수화 지속(고유 4,242/25,818, 싱글톤 62.6%)이 실측돼 보류 해제. 운영 빈도 데이터 주도로 **고정 어휘 172개·13축**(V41 `trait_taxonomy` 시드, 사용자 확정) + 합성 프롬프트 어휘 주입(`instructions(vocab)` — Anthropic static 캡처 해소) + 저장 sanitize(어휘 밖 드롭·중복 제거, 전부 드롭 시 빈 배열) + 기존 데이터 이행은 어드민 원샷 잡 `TRAIT_CANON_DRY/APPLY`(LLM 배치 매핑 → `trait_canon_log` 감사 → traits in-place UPDATE). **1:N 분해 매핑 채택**(복합 trait→원자 태그 최대 2, "감성 브이로그"→[브이로그, 감성 무드]) — [specs/2026-07-29-trait-vocabulary-control-design.md](../superpowers/specs/2026-07-29-trait-vocabulary-control-design.md) [plans/2026-07-29-trait-vocabulary-control.md](../superpowers/plans/2026-07-29-trait-vocabulary-control.md)
