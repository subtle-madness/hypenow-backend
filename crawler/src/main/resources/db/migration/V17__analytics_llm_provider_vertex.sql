-- 분석 LLM baseline 프로바이더를 gemini → vertex로 승격 (2026-07-20 후속).
--
-- 배경: V16이 analytics.llm-provider를 gemini로 시드했으나, Vertex AI($300 크레딧) 전환이 확정돼
-- baseline을 vertex로 올린다. V16은 이미 운영 raw DB에 적용돼(체크섬 고정) 인플레이스 수정이
-- 불가하므로 신규 마이그레이션으로 옮긴다(원칙: 기준값 변경은 후속 마이그레이션, CLAUDE.md 참조).
--
-- 형태 선택: INSERT ... ON CONFLICT DO NOTHING은 V16이 이미 같은 키를 시드해 무조건 무효(기록 전용)라
-- 신규·재생성 환경도 gemini에 머문다. 그래서 "아직 시드 기본값(gemini)인 경우에만" 올리는 조건부 UPDATE를 쓴다:
--   * 운영: 이미 런타임 UPDATE로 'vertex' → WHERE value='gemini' 불일치 → **건드리지 않음**(런타임 값 보존).
--   * 신규/재생성: V16이 넣은 'gemini' → 'vertex'로 승격(baseline 의도 반영).
--   * 명시적으로 vertex/anthropic 등 다른 값으로 전환해 둔 환경: 불일치라 보존.
-- SA 키가 없는 환경은 LlmConfig.useVertex 그레이스풀 폴백으로 무료 gemini를 타므로 안전.
UPDATE app_setting SET value = 'vertex'
 WHERE key = 'analytics.llm-provider' AND value = 'gemini';
