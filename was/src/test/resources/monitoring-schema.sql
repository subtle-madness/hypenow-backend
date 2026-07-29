-- 계약 §3(v0.1)에서 유도한 monitoring DB 픽스처 — 테스트 전용.
-- monitoring/src/main/resources/db/migration/V1__core_tables.sql과 대조 완료(2026-07-29) — 계약 변경 시 재대조.
CREATE TABLE IF NOT EXISTS target (
    id                 bigserial PRIMARY KEY,
    type               text NOT NULL,             -- ACCOUNT / POST
    username           text NOT NULL,
    short_code         text,
    keyword_rule       jsonb,
    status             text NOT NULL,             -- WATCHING/TRACKING/EXPIRED/CANCELED/FAILED
    tracked_short_code text,
    tracked_since      timestamptz,
    registration_key   text NOT NULL UNIQUE,
    expires_at         timestamptz NOT NULL,
    registered_at      timestamptz NOT NULL DEFAULT now(),
    closed_at          timestamptz,
    last_fetched_at    timestamptz,
    fail_reason        text
);

CREATE TABLE IF NOT EXISTS detected_candidate (
    id              bigserial PRIMARY KEY,
    target_id       bigint NOT NULL,
    short_code      text NOT NULL,
    detected_at     timestamptz NOT NULL,
    caption_excerpt text,
    status          text NOT NULL,                -- PENDING/APPROVED/REJECTED
    UNIQUE (target_id, short_code)
);

CREATE TABLE IF NOT EXISTS profile_snapshot (
    username    text NOT NULL,
    captured_on date NOT NULL,
    followers   bigint,
    following   bigint,
    media_count bigint,
    PRIMARY KEY (username, captured_on)
);

CREATE TABLE IF NOT EXISTS post_snapshot (
    username     text NOT NULL,
    short_code   text NOT NULL,
    captured_on  date NOT NULL,
    content_type text,                            -- REELS / FEED
    likes        bigint,
    comments     bigint,
    views        bigint,                          -- 피드는 항상 NULL (계약 §3 null 규칙)
    saves        bigint,
    shares       bigint,
    reposts      bigint,
    PRIMARY KEY (short_code, captured_on)
);
