-- ===== 규칙 (UI 편집) =====
CREATE TABLE category (
    id      bigserial PRIMARY KEY,
    name    text      NOT NULL UNIQUE,
    enabled boolean   NOT NULL DEFAULT true
);

CREATE TABLE category_keyword (
    id          bigserial PRIMARY KEY,
    category_id bigint    NOT NULL REFERENCES category(id),
    keyword     text      NOT NULL,
    enabled     boolean   NOT NULL DEFAULT true,
    UNIQUE (category_id, keyword)
);

CREATE TABLE collection_rule (
    id            bigserial PRIMARY KEY,
    category_id   bigint    NOT NULL UNIQUE REFERENCES category(id),
    min_followers integer,
    max_followers integer,
    content_types text      NOT NULL DEFAULT 'ALL'
);

-- ===== 제어 인덱스 =====
CREATE TABLE account (
    id               bigserial   PRIMARY KEY,
    username         text        NOT NULL UNIQUE,
    last_profiled_at timestamptz
);

CREATE TABLE content (
    id                 bigserial   PRIMARY KEY,
    short_code         text        NOT NULL UNIQUE,
    content_type       text        NOT NULL,
    owner_username     text        NOT NULL,
    uploaded_at        timestamptz NOT NULL,
    category_id        bigint      NOT NULL REFERENCES category(id),
    discovery_keyword  text        NOT NULL,
    status             text        NOT NULL DEFAULT 'PENDING',
    first_seen_at      timestamptz NOT NULL,
    qualified_at       timestamptz,
    aggregated_at      timestamptz,
    aggregate_attempts integer     NOT NULL DEFAULT 0
);
CREATE INDEX idx_content_status ON content(status);
CREATE INDEX idx_content_uploaded_at ON content(uploaded_at);

CREATE TABLE crawl_run (
    id            bigserial   PRIMARY KEY,
    job           text        NOT NULL,
    trigger_type  text        NOT NULL,
    category_id   bigint      REFERENCES category(id),
    keyword       text,
    actor_id      text        NOT NULL,
    apify_run_id  text,
    status        text        NOT NULL,
    item_count    integer,
    error_message text,
    started_at    timestamptz NOT NULL,
    finished_at   timestamptz
);

-- ===== raw (Apify 응답 verbatim, 액터별 테이블) =====
-- generated column은 조회 편의용 파생값. Apify 필드명이 바뀌면 여기만 마이그레이션.
-- 주의: ::bigint 캐스트는 값이 숫자 문자열이 아니면 insert가 실패한다 —
--       스모크 테스트에서 실제 응답 필드명·형식 확인 후 필요 시 V2로 조정.
CREATE TABLE raw_discovery_post (
    id           bigserial   PRIMARY KEY,
    content_id   bigint      NOT NULL REFERENCES content(id),
    crawl_run_id bigint      NOT NULL REFERENCES crawl_run(id),
    payload      jsonb       NOT NULL,
    captured_at  timestamptz NOT NULL,
    short_code   text GENERATED ALWAYS AS (payload->>'shortCode') STORED,
    caption      text GENERATED ALWAYS AS (payload->>'caption') STORED
);
CREATE INDEX idx_raw_discovery_post_content ON raw_discovery_post(content_id);

CREATE TABLE raw_post_detail (
    id               bigserial   PRIMARY KEY,
    content_id       bigint      NOT NULL REFERENCES content(id),
    crawl_run_id     bigint      NOT NULL REFERENCES crawl_run(id),
    payload          jsonb       NOT NULL,
    captured_at      timestamptz NOT NULL,
    short_code       text   GENERATED ALWAYS AS (payload->>'shortCode') STORED,
    caption          text   GENERATED ALWAYS AS (payload->>'caption') STORED,
    likes            bigint GENERATED ALWAYS AS ((payload->>'likesCount')::bigint) STORED,
    comments_count   bigint GENERATED ALWAYS AS ((payload->>'commentsCount')::bigint) STORED,
    video_play_count bigint GENERATED ALWAYS AS ((payload->>'videoPlayCount')::bigint) STORED
);
CREATE INDEX idx_raw_post_detail_content ON raw_post_detail(content_id);

CREATE TABLE raw_comment (
    id           bigserial   PRIMARY KEY,
    content_id   bigint      NOT NULL REFERENCES content(id),
    crawl_run_id bigint      NOT NULL REFERENCES crawl_run(id),
    payload      jsonb       NOT NULL,
    captured_at  timestamptz NOT NULL,
    writer       text GENERATED ALWAYS AS (payload->>'ownerUsername') STORED,
    text         text GENERATED ALWAYS AS (payload->>'text') STORED,
    written_at   text GENERATED ALWAYS AS (payload->>'timestamp') STORED
);
CREATE INDEX idx_raw_comment_content ON raw_comment(content_id);

CREATE TABLE raw_profile (
    id           bigserial   PRIMARY KEY,
    account_id   bigint      NOT NULL REFERENCES account(id),
    crawl_run_id bigint      NOT NULL REFERENCES crawl_run(id),
    payload      jsonb       NOT NULL,
    captured_at  timestamptz NOT NULL,
    username     text   GENERATED ALWAYS AS (payload->>'username') STORED,
    followers    bigint GENERATED ALWAYS AS ((payload->>'followersCount')::bigint) STORED
);
CREATE INDEX idx_raw_profile_account ON raw_profile(account_id);
