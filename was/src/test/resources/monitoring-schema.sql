-- 계약 §3(v0.1)에서 유도한 monitoring DB 픽스처 — 테스트 전용.
-- ⚠️ monitoring 구현 확정 시 실제 스키마와 대조 필요(스펙 §7 잔여 작업).
-- P1 확장 선반영(docs/contracts/monitoring-v3-extension-request.md) — monitoring 실구현 확정 시 대조
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
    fail_reason        text,
    tracked_hidden_at  timestamptz,
    fetch_failing      boolean NOT NULL DEFAULT false
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

CREATE TABLE IF NOT EXISTS post_meta (
    short_code    text PRIMARY KEY,
    username      text NOT NULL,
    content_type  text,
    uploaded_at   date NOT NULL,
    caption       text NOT NULL,
    thumbnail_url text,
    first_seen_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS post_comment (
    id               text NOT NULL,
    short_code       text NOT NULL,
    author           text NOT NULL,
    body             text NOT NULL,
    like_count       bigint NOT NULL,
    commented_at     timestamptz NOT NULL,
    owner_reply_text text,
    PRIMARY KEY (short_code, id)
);
CREATE TABLE IF NOT EXISTS sweep_run (
    id           bigserial PRIMARY KEY,
    started_at   timestamptz NOT NULL,
    completed_at timestamptz,
    ok           boolean
);
