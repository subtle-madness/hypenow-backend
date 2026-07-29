-- 원형 스키마: was 무권한(GRANT 자체를 안 줌). 롤링 삭제 대상.
CREATE SCHEMA IF NOT EXISTS raw;

CREATE TABLE raw.fetch_payload (
    id          bigserial PRIMARY KEY,
    kind        text        NOT NULL,  -- PROFILE / POSTS / POST
    subject     text        NOT NULL,  -- username 또는 short_code
    fetched_at  timestamptz NOT NULL DEFAULT now(),
    http_status int         NOT NULL,
    payload     jsonb       NOT NULL
);
CREATE INDEX idx_fetch_payload_subject ON raw.fetch_payload (subject, fetched_at);

-- 캠페인 단위 (등록마다 1행 — 같은 계정도 캠페인별 별도. 스펙 결정 10)
CREATE TABLE target (
    id                 bigserial   PRIMARY KEY,
    type               text        NOT NULL,          -- ACCOUNT / POST
    username           text        NOT NULL,
    short_code         text,                          -- POST 등록 시의 게시물
    keyword_rule       jsonb,                         -- {"and":[],"any":[],"exclude":[]} (ACCOUNT 전용)
    status             text        NOT NULL,          -- WATCHING/TRACKING/EXPIRED/CANCELED/FAILED
    tracked_short_code text,
    tracked_since      timestamptz,
    registration_key   text        NOT NULL UNIQUE,   -- was 생성 멱등 키
    expires_at         timestamptz NOT NULL,
    registered_at      timestamptz NOT NULL DEFAULT now(),
    closed_at          timestamptz,
    last_fetched_at    timestamptz,
    fail_reason        text
);
CREATE INDEX idx_target_active ON target (username) WHERE status IN ('WATCHING', 'TRACKING');

CREATE TABLE detected_candidate (
    id              bigserial   PRIMARY KEY,
    target_id       bigint      NOT NULL REFERENCES target (id),
    short_code      text        NOT NULL,
    detected_at     timestamptz NOT NULL DEFAULT now(),
    caption_excerpt text,
    status          text        NOT NULL DEFAULT 'PENDING',  -- PENDING/APPROVED/REJECTED
    UNIQUE (target_id, short_code)   -- 재감지 중복 생성 방지 (거절돼도 되살아나지 않음)
);

-- 관측 대상 단위 (캠페인 수와 무관 — 계정·게시물당 1행/일)
CREATE TABLE profile_snapshot (
    username    text   NOT NULL,
    captured_on date   NOT NULL,
    followers   bigint,
    following   bigint,
    media_count bigint,
    PRIMARY KEY (username, captured_on)
);

CREATE TABLE post_snapshot (
    username     text   NOT NULL,
    short_code   text   NOT NULL,
    captured_on  date   NOT NULL,
    content_type text,             -- REELS / FEED
    likes        bigint,
    comments     bigint,
    views        bigint,
    saves        bigint,
    shares       bigint,
    reposts      bigint,
    PRIMARY KEY (short_code, captured_on)
);
CREATE INDEX idx_post_snapshot_username ON post_snapshot (username, captured_on);
