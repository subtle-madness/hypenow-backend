-- 계약 §3에서 유도한 monitoring DB 픽스처 — 테스트 전용.
-- 2026-07-30 계약 v1.1(feat/monitoring-v3-p2, V4__p2_surfaces.sql)과 대조 완료:
--   post_comment·profile_meta·detected_candidate.matched_keywords는 실구현 DDL과 일치.
-- P1 확장 선반영분(docs/contracts/monitoring-v3-extension-request.md — post_meta·
--   target.tracked_hidden_at·fetch_failing·sweep_run)은 아직 실구현 미착수 —
--   v2.0(feat/monitoring-alarm-module) 재편 확정 시 재대조할 것.
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
    id               bigserial PRIMARY KEY,
    target_id        bigint NOT NULL,
    short_code       text NOT NULL,
    detected_at      timestamptz NOT NULL,
    caption_excerpt  text,
    status           text NOT NULL,               -- PENDING/APPROVED/REJECTED
    matched_keywords jsonb,                       -- v1.1 이전 감지분은 null(was는 빈 배열 폴백)
    UNIQUE (target_id, short_code)
);

-- 계정 표시 메타 — 계정 단위 최신 1행(계약 v1.1 §3 profile_meta)
CREATE TABLE IF NOT EXISTS profile_meta (
    username          text PRIMARY KEY,
    display_name      text,
    profile_image_url text,
    last_uploaded_at  date,
    updated_at        timestamptz NOT NULL
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
