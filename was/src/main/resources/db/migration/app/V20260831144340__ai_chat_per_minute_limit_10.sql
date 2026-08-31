-- 분당 질문 상한 기준값 상향 5 → 10 (2026-08-31 한계 재도출, 스펙 §4 - 사용자 지시)
-- 운영자가 런타임에 손댄 값은 존중한다 - 시드 기본값(5) 그대로인 행만 올린다.
UPDATE app.app_setting SET value = '10'
WHERE key = 'ai.chat.per-minute-limit' AND value = '5';
