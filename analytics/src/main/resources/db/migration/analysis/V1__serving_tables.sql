-- 미러 테이블 3종 (ARCHITECTURE.md §4-3: 저장은 Flyway DDL 소유).
-- 컬럼 이름·순서 = 서빙 뷰 = contract-analysis record. 자연키 PK (미러 전체 교체에도 id 안정).
-- 분석 층 테이블과의 FK는 걸지 않는다 (TRUNCATE와 충돌 — 논리 참조만).
CREATE TABLE accounts (
    handle            text PRIMARY KEY,
    display_name      text,
    profile_image_url text,
    followers         bigint
);

CREATE TABLE contents (
    short_code     text PRIMARY KEY,
    account_handle text NOT NULL,
    thumbnail_url  text,
    caption        text,
    posted_at      timestamptz,
    content_type   text,
    video_duration numeric,
    original_url   text,
    views          bigint,
    likes          bigint,
    comments       bigint,
    hype_score     bigint
);
CREATE INDEX idx_contents_hype_score ON contents (hype_score DESC NULLS LAST);
CREATE INDEX idx_contents_account_handle ON contents (account_handle);

CREATE TABLE content_comments (
    id            bigint PRIMARY KEY,
    short_code    text NOT NULL,
    author_masked text,
    body          text,
    like_count    bigint
);
CREATE INDEX idx_content_comments_short_code ON content_comments (short_code);
