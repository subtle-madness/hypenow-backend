CREATE TABLE app_setting (
    key   text PRIMARY KEY,
    value text NOT NULL
);

-- 자체크롤 런타임 토글 기준값(개통 전 전량 off = 행동 변화 0). 기준값은 Flyway로 시드(CLAUDE.md).
INSERT INTO app_setting (key, value) VALUES
    ('ig-source.self-enabled', 'false'),
    ('ig-source.force-hiker', 'false'),
    ('ig-source.profile-surface', 'wpi')
ON CONFLICT (key) DO NOTHING;
