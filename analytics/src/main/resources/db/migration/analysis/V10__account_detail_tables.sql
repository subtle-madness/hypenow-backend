-- 인플루언서 상세 미러 3종 (ARCHITECTURE.md §4-3: 저장은 Flyway DDL 소유).
-- 컬럼 이름·순서 = 서빙 뷰(10_account_detail.sql) = contract record. 자연키 PK.
-- 분석 층 테이블과의 FK는 걸지 않는다 (TRUNCATE와 충돌 — 논리 참조만).
CREATE TABLE account_summaries (
    handle                   text PRIMARY KEY,
    followers                bigint,
    follows_count            bigint,
    posts_count              bigint,
    biography                text,
    analyzed_count           bigint,
    views_count              bigint,
    metric                   text,
    avg_views                bigint,
    views_per_follower       numeric,
    avg_er_pct               numeric,
    avg_likes                bigint,
    avg_comments             bigint,
    trend_direction          text,
    trend_change_pct         integer,
    trend_older_avg          bigint,
    trend_newer_avg          bigint,
    sponsored_count          bigint,
    organic_avg              bigint,
    ad_avg                   bigint,
    ad_drop_pct              integer,
    comparison_organic_count bigint,
    comparison_ad_count      bigint,
    last_ad_posted_at        timestamptz,
    last_posted_at           timestamptz,
    avg_interval_days        numeric
);

CREATE TABLE account_category_stats (
    account_handle text NOT NULL,
    main_group     text NOT NULL,
    content_count  bigint,
    PRIMARY KEY (account_handle, main_group)
);

CREATE TABLE account_content_series (
    short_code     text PRIMARY KEY,
    account_handle text NOT NULL,
    posted_at      timestamptz,
    content_type   text,
    views          bigint,
    likes          bigint,
    comments       bigint,
    sponsored      boolean
);
CREATE INDEX idx_account_content_series_handle ON account_content_series (account_handle);
-- account_category_stats는 PK 선두 컬럼이 account_handle이라 별도 인덱스 불필요.
