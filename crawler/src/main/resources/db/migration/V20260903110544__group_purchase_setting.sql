-- 공동구매(공구) 판정기 킬 스위치 기준값(2026-09-03, 규칙 우선 + 애매분만 LLM 판정 스펙) —
-- 기본 true(판정 활성). false로 내리면 analytics GroupPurchaseJudgeJob이 아무 것도 하지 않고
-- 즉시 반환한다. 전환·롤백은 수동 UPDATE.
INSERT INTO app_setting (key, value)
VALUES ('analytics.group-purchase.enabled', 'true')
ON CONFLICT (key) DO NOTHING;
