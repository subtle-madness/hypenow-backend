-- 브랜드 해시태그 제안 런타임 설정(2026-09-03 자동 시드 재설계 §3-5).
-- min-posts  : 태그된 게시물 캡션 집계에서 최다 태그의 "등장 게시물 수"가 이 값 이상이면 path=FREQ.
-- stoplist   : FREQ 후보·AI 결과 양쪽에서 제외할 태그(쉼표 구분, 소문자 비교).
-- ai-enabled : 2순위 AI 경로 킬 스위치. 끄면 FREQ 미달이 곧장 FALLBACK(계정명 정리)으로 간다:
--   UPDATE app_setting SET value = 'false' WHERE key = 'brand.hashtag-seed.ai-enabled';
-- (재배포 불필요 — BrandHashtagSeedSettings TTL 5초 이내 반영)
INSERT INTO app_setting (key, value) VALUES
    ('brand.hashtag-seed.min-posts', '7'),
    ('brand.hashtag-seed.stoplist', '광고,협찬,이벤트,공구,체험단,유료광고,광고포함,ad,sponsored,pr'),
    ('brand.hashtag-seed.ai-enabled', 'true')
ON CONFLICT (key) DO NOTHING;
