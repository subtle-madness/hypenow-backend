-- 계약 §3에서 유도한 monitoring DB 픽스처 — 테스트 전용.
-- 2026-07-30 계약 v1.1(feat/monitoring-v3-p2, V4__p2_surfaces.sql)과 대조 완료:
--   post_comment·profile_meta·detected_candidate.matched_keywords는 실구현 DDL과 일치.
-- 2026-07-30 계약 v2.1(feat/monitoring-alarm-module, V3__user_id_and_alarm_event.sql)과 대조 완료:
--   alarm_event는 was 소비 컬럼(id·target_id·user_id·event_type·payload·occurred_at)만 축약해
--   실구현 DDL과 일치(email_status·dispatch_after 등 monitoring 내부 컬럼도 동봉해 실 스키마와 형태를 맞춘다).
-- 2026-07-30 계약 v2.2(V5__p1_surfaces.sql)와 재대조 완료: post_meta·sweep_run·
--   target.tracked_hidden_at·fetch_failing은 이미 실구현과 동일 형태였고, target.user_id(V3)·
--   matched_keywords(V5)만 빠져 있어 이번에 추가했다. v_target_overview는 조회 표면 계약에
--   포함되지만 was는 뷰가 아닌 베이스 테이블만 SELECT하므로(두 뷰를 조인하지 말라는 계약 주의사항과
--   무관하게 어셈블러는 베이스 테이블 직접 조회) 픽스처에 뷰를 별도로 만들지 않는다.
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
    user_id            bigint,                    -- 알람 수신자(V3) — V3 이전 등록분은 null
    tracked_hidden_at  timestamptz,
    fetch_failing      boolean NOT NULL DEFAULT false,
    matched_keywords   jsonb                       -- 감지 자동 전환 시 실제 매칭 키워드(V5) — POST 등록·감지 전은 null
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
-- image_object_path·image_source_name·image_archived_at은 monitoring 자체 프로필 이미지
-- 아카이브(설계 스펙 §3-1, V20260730192350) 추가 컬럼 — was는 image_object_path만 읽는다.
CREATE TABLE IF NOT EXISTS profile_meta (
    username           text PRIMARY KEY,
    display_name       text,
    profile_image_url  text,
    last_uploaded_at   date,
    updated_at         timestamptz NOT NULL,
    image_object_path  text,
    image_source_name  text,
    image_archived_at  timestamptz
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

-- image_object_path·image_source_name·image_archived_at은 monitoring 자체 게시물 썸네일
-- 아카이브(트랙 KK 확장, V20260801064345) 추가 컬럼 — was는 image_object_path만 읽는다.
CREATE TABLE IF NOT EXISTS post_meta (
    short_code         text PRIMARY KEY,
    username           text NOT NULL,
    content_type       text,
    uploaded_at        date NOT NULL,
    caption            text NOT NULL,
    thumbnail_url      text,
    first_seen_at      timestamptz NOT NULL DEFAULT now(),
    image_object_path  text,
    image_source_name  text,
    image_archived_at  timestamptz
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

-- 알람 이벤트 대장(v2.1 §3) — 앱 내 다이제스트·히스토리의 단일 원천. 실 DDL(V3)과 동일 형태.
CREATE TABLE IF NOT EXISTS alarm_event (
    id             bigserial   PRIMARY KEY,
    target_id      bigint      NOT NULL,
    user_id        bigint      NOT NULL,
    event_type     text        NOT NULL CHECK (event_type IN
                   ('COLLECTION_STARTED','COLLECTION_ENDED','METRICS_HIDDEN','CONTENT_UNAVAILABLE')),
    payload        jsonb       NOT NULL,
    occurred_at    timestamptz NOT NULL DEFAULT now(),
    dispatch_after timestamptz NOT NULL,
    email_status   text        NOT NULL DEFAULT 'PENDING' CHECK (email_status IN
                   ('PENDING','SENT','SKIPPED_OPTOUT','SKIPPED_NO_RECIPIENT','FAILED')),
    email_attempts smallint    NOT NULL DEFAULT 0,
    email_sent_at  timestamptz
);
