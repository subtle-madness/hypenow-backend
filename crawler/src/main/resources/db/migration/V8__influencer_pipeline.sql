-- V8__influencer_pipeline.sql
-- 인플루언서 중심 전환: 스키마 + 같은 DB 안 데이터 이관 (스펙 2026-07-14)

-- ===== 1) search_keyword (분류 평탄화) =====
CREATE TABLE search_keyword (
    id         bigserial   PRIMARY KEY,
    keyword    text        NOT NULL UNIQUE,
    enabled    boolean     NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now()
);
INSERT INTO search_keyword (keyword, enabled)
SELECT keyword, bool_or(enabled) FROM category_keyword GROUP BY keyword;

-- ===== 2) account → influencer =====
ALTER TABLE account RENAME TO influencer;
ALTER SEQUENCE account_id_seq RENAME TO influencer_id_seq;
ALTER TABLE influencer RENAME CONSTRAINT account_username_key TO influencer_username_key;
ALTER TABLE influencer
    ADD COLUMN status             text NOT NULL DEFAULT 'DISCOVERED',
    ADD COLUMN followers          bigint,
    ADD COLUMN first_collected_at timestamptz,
    ADD COLUMN last_collected_at  timestamptz;
CREATE INDEX idx_influencer_status ON influencer(status);

-- 프로필 있는 계정은 최신 raw_profile 팔로워 복사 (판정은 새 qualify가 재수행)
UPDATE influencer i SET followers = rp.followers
FROM (SELECT DISTINCT ON (account_id) account_id, (payload->>'followersCount')::bigint AS followers
      FROM raw_profile ORDER BY account_id, captured_at DESC) rp
WHERE rp.account_id = i.id;

ALTER TABLE raw_profile RENAME COLUMN account_id TO influencer_id;
ALTER INDEX idx_raw_profile_account RENAME TO idx_raw_profile_influencer;

-- ===== 3) influencer_discovery (발굴 출처, 키워드는 텍스트 스냅샷) =====
CREATE TABLE influencer_discovery (
    id                         bigserial   PRIMARY KEY,
    influencer_id              bigint      NOT NULL REFERENCES influencer(id),
    keyword                    text        NOT NULL,
    discovered_post_short_code text,
    discovered_at              timestamptz NOT NULL
);
CREATE INDEX idx_influencer_discovery_influencer ON influencer_discovery(influencer_id);
INSERT INTO influencer_discovery (influencer_id, keyword, discovered_post_short_code, discovered_at)
SELECT i.id, c.discovery_keyword, c.short_code, c.first_seen_at
FROM content c JOIN influencer i ON i.username = c.owner_username;

-- ===== 4) content 재편 =====
ALTER TABLE content
    ADD COLUMN influencer_id    bigint REFERENCES influencer(id),
    ADD COLUMN collected_at     timestamptz,
    ADD COLUMN collect_attempts integer NOT NULL DEFAULT 0;
UPDATE content c SET influencer_id = i.id FROM influencer i WHERE i.username = c.owner_username;
ALTER TABLE content ALTER COLUMN influencer_id SET NOT NULL;
UPDATE content SET collected_at = aggregated_at, collect_attempts = aggregate_attempts;
-- 상태 재매핑: 판정 상태는 인플루언서로 이동, 게시물은 수집 여부만
UPDATE content SET status = CASE
    WHEN status = 'AGGREGATED' THEN 'COLLECTED'
    WHEN status IN ('GONE', 'FAILED') THEN 'FAILED'
    ELSE 'PENDING' END;
ALTER TABLE content
    DROP COLUMN category_id,
    DROP COLUMN discovery_keyword,
    DROP COLUMN subcategory,
    DROP COLUMN main_group,
    DROP COLUMN ad_marked,
    DROP COLUMN qualified_at,
    DROP COLUMN aggregated_at,
    DROP COLUMN aggregate_attempts;
CREATE INDEX idx_content_influencer ON content(influencer_id);

-- ===== 5) crawl_run: 카테고리 제거, 대상 인플루언서 기록 =====
ALTER TABLE crawl_run
    DROP COLUMN category_id,
    ADD COLUMN target_username text;

-- ===== 6) raw 원형화: source 태그 + generated → 실컬럼 =====
-- 기존 payload는 정규화 envelope이므로 LEGACY_ENVELOPE로 표시. 새 코드는 source를 명시 저장.
ALTER TABLE raw_discovery_post ADD COLUMN source text NOT NULL DEFAULT 'LEGACY_ENVELOPE';
ALTER TABLE raw_post_detail    ADD COLUMN source text NOT NULL DEFAULT 'LEGACY_ENVELOPE';
ALTER TABLE raw_comment        ADD COLUMN source text NOT NULL DEFAULT 'LEGACY_ENVELOPE';
ALTER TABLE raw_profile        ADD COLUMN source text NOT NULL DEFAULT 'LEGACY_ENVELOPE';
ALTER TABLE raw_discovery_post ALTER COLUMN source DROP DEFAULT;
ALTER TABLE raw_post_detail    ALTER COLUMN source DROP DEFAULT;
ALTER TABLE raw_comment        ALTER COLUMN source DROP DEFAULT;
ALTER TABLE raw_profile        ALTER COLUMN source DROP DEFAULT;

-- generated column을 실컬럼으로 (drop → add → 기존값 backfill). 추출 실패 시 NULL 허용이 목적.
ALTER TABLE raw_discovery_post DROP COLUMN short_code, DROP COLUMN caption;
ALTER TABLE raw_discovery_post ADD COLUMN short_code text, ADD COLUMN caption text;
UPDATE raw_discovery_post SET short_code = payload->>'shortCode', caption = payload->>'caption';

ALTER TABLE raw_post_detail DROP COLUMN short_code, DROP COLUMN caption, DROP COLUMN likes,
                            DROP COLUMN comments_count, DROP COLUMN video_play_count;
ALTER TABLE raw_post_detail ADD COLUMN short_code text, ADD COLUMN caption text, ADD COLUMN likes bigint,
                            ADD COLUMN comments_count bigint, ADD COLUMN video_play_count bigint;
UPDATE raw_post_detail SET short_code = payload->>'shortCode', caption = payload->>'caption',
    likes = (payload->>'likesCount')::bigint, comments_count = (payload->>'commentsCount')::bigint,
    video_play_count = (payload->>'videoPlayCount')::bigint;

ALTER TABLE raw_comment DROP COLUMN writer, DROP COLUMN text, DROP COLUMN written_at;
ALTER TABLE raw_comment ADD COLUMN writer text, ADD COLUMN text text, ADD COLUMN written_at text;
UPDATE raw_comment SET writer = payload->>'ownerUsername', text = payload->>'text',
    written_at = payload->>'timestamp';

ALTER TABLE raw_profile DROP COLUMN username, DROP COLUMN followers;
ALTER TABLE raw_profile ADD COLUMN username text, ADD COLUMN followers bigint;
UPDATE raw_profile SET username = payload->>'username', followers = (payload->>'followersCount')::bigint;

-- ===== 7) raw_media_page (열거 페이지 원형) =====
CREATE TABLE raw_media_page (
    id            bigserial   PRIMARY KEY,
    influencer_id bigint      NOT NULL REFERENCES influencer(id),
    crawl_run_id  bigint      NOT NULL REFERENCES crawl_run(id),
    source        text        NOT NULL,
    payload       jsonb       NOT NULL,
    captured_at   timestamptz NOT NULL
);
CREATE INDEX idx_raw_media_page_influencer ON raw_media_page(influencer_id);

-- ===== 8) 구 테이블 정리 =====
DROP TABLE collection_rule;
DROP TABLE category_keyword;
DROP TABLE category;
