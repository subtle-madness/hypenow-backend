-- 브랜드 태그 모니터링 테이블 픽스처 — 테스트 전용(was는 이 테이블들을 읽기만 한다).
-- 정본은 monitoring/src/main/resources/db/migration/V20260806150000__brand_tag_monitoring.sql +
-- V20260807130000__brand_was_contract_fields.sql — 두 마이그레이션의 최종 형태를 여기에 접어 넣었다
-- (2026-08-07 대조 완료). 기존 monitoring-schema.sql(캠페인 계약 픽스처)과는 서로 무관하므로
-- 별도 파일로 두고, 브랜드 테스트만 이 스크립트를 적용한다.
-- was가 읽지 않는 brand_profile_snapshot은 픽스처에 두지 않는다(읽기 표면만 재현).

CREATE TABLE IF NOT EXISTS brand_account (
    id                    bigserial   PRIMARY KEY,
    username              text        NOT NULL UNIQUE,
    ig_user_id            text        NOT NULL,
    followers             bigint,
    biography             text,
    status                text        NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'CLOSED')),
    registered_at         timestamptz NOT NULL DEFAULT now(),
    closed_at             timestamptz,
    last_swept_on         date,
    full_name             text,
    profile_pic_url       text,
    is_verified           boolean,
    external_url          text,
    following             bigint,
    media_count           bigint,
    backfill_error        text,
    backfill_completed_at timestamptz,
    last_swept_at         timestamptz,
    -- 이미지 아카이브 3컬럼(V20260811023454) — was는 image_object_path만 읽는다.
    image_object_path     text,
    image_source_name     text,
    image_archived_at     timestamptz
);

CREATE TABLE IF NOT EXISTS brand_tagged_post (
    brand_id                 bigint      NOT NULL REFERENCES brand_account (id),
    short_code               text        NOT NULL,
    author_username          text        NOT NULL,
    author_ig_user_id        text,
    taken_at                 timestamptz NOT NULL,
    first_seen_at            timestamptz NOT NULL DEFAULT now(),
    comments_collected_count bigint      NOT NULL DEFAULT 0,
    PRIMARY KEY (brand_id, short_code)
);

CREATE TABLE IF NOT EXISTS brand_post_snapshot (
    username      text    NOT NULL,
    short_code    text    NOT NULL,
    captured_on   date    NOT NULL,
    content_type  text,
    likes         bigint,
    likes_hidden  boolean NOT NULL DEFAULT false,
    comments      bigint,
    views         bigint,   -- 화면 합산값(IG 몫 + FB 몫)
    fb_plays      bigint,
    saves         bigint,
    shares        bigint,
    shares_hidden boolean NOT NULL DEFAULT false,
    reposts       bigint,
    PRIMARY KEY (short_code, captured_on)
);

CREATE TABLE IF NOT EXISTS brand_post_meta (
    short_code          text        PRIMARY KEY,
    username            text        NOT NULL,
    content_type        text,
    uploaded_at         date        NOT NULL,
    caption             text        NOT NULL,
    thumbnail_url       text,
    first_seen_at       timestamptz NOT NULL DEFAULT now(),
    video_url           text,
    video_duration      double precision,
    is_paid_partnership boolean,
    -- 이미지 아카이브 3컬럼(V20260812021500) — was는 image_object_path만 읽는다.
    image_object_path   text,
    image_source_name   text,
    image_archived_at   timestamptz
);

CREATE TABLE IF NOT EXISTS brand_post_comment (
    short_code       text        NOT NULL,
    id               text        NOT NULL,
    author           text        NOT NULL,
    body             text        NOT NULL,
    like_count       bigint      NOT NULL,
    commented_at     timestamptz NOT NULL,
    owner_reply_text text,
    PRIMARY KEY (short_code, id)
);

-- 정본은 monitoring/src/main/resources/db/migration/V20260811085943__brand_hashtag_detection.sql —
-- was가 읽는 컬럼만 이 픽스처에도 맞춰 둔다(verdict_source는 was 미소비라 생략 없이 그대로 둔다,
-- 마이그레이션의 CHECK 제약과 어긋나면 오류가 나야 픽스처 표류를 바로 잡을 수 있다).
CREATE TABLE IF NOT EXISTS brand_hashtag_post (
    brand_id               bigint      NOT NULL REFERENCES brand_account (id),
    short_code             text        NOT NULL,
    matched_tag            text        NOT NULL,
    author_username        text        NOT NULL,
    author_full_name       text,
    author_profile_pic_url text,
    taken_at               timestamptz NOT NULL,
    caption                text        NOT NULL DEFAULT '',
    content_type           text,
    thumbnail_url          text,
    likes                  bigint,
    comments               bigint,
    verdict                text        NOT NULL CHECK (verdict IN
                               ('RELEVANT', 'UNCERTAIN', 'IRRELEVANT', 'SELF', 'DIRECT_TAGGED')),
    verdict_source         text        NOT NULL CHECK (verdict_source IN ('RULE', 'MENTION', 'LLM')),
    first_seen_at          timestamptz NOT NULL DEFAULT now(),
    -- 이미지 아카이브 3컬럼(V20260812021500) — was는 image_object_path만 읽는다.
    image_object_path      text,
    image_source_name      text,
    image_archived_at      timestamptz,
    PRIMARY KEY (brand_id, short_code)
);

CREATE TABLE IF NOT EXISTS author_profile (
    ig_user_id      text        PRIMARY KEY,
    username        text        NOT NULL,
    full_name       text,
    followers       bigint,
    following       bigint,
    media_count     bigint,
    biography       text,
    profile_pic_url text,
    is_private      boolean,
    fetched_at      timestamptz NOT NULL,
    is_verified     boolean,
    -- 이미지 아카이브 3컬럼(V20260807150500) — was는 image_object_path만 읽는다.
    image_object_path text,
    image_source_name text,
    image_archived_at timestamptz
);

-- 브랜드별 Hiker 콜 일별 집계(정본: monitoring V20260812100000__brand_call_count.sql).
CREATE TABLE IF NOT EXISTS brand_call_count (
    brand_id  bigint NOT NULL,
    called_on date   NOT NULL,
    calls     bigint NOT NULL DEFAULT 0,
    PRIMARY KEY (brand_id, called_on)
);
