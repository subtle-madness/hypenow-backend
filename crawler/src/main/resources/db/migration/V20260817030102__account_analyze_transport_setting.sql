-- 계정 카피 전송 방식 기준값(2026-08-17) — online(기본)|batch. 콘텐츠 토글(analytics.analyze-transport)과
-- 독립: 08-11 콘텐츠 전환 때 계정 카피는 의도적으로 제외됐던 후속 트랙. 전환·롤백은 수동 UPDATE.
INSERT INTO app_setting (key, value)
VALUES ('analytics.account-analyze-transport', 'online')
ON CONFLICT (key) DO NOTHING;
