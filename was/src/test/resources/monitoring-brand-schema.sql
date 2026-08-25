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
    image_archived_at     timestamptz,
    -- 수집 범위 선택(V20260812220000) — was는 둘 다 읽는다(collection_started_at은 registered_at 폴백).
    collection_months     int         NOT NULL DEFAULT 12
                              CHECK (collection_months IN (1, 3, 6, 12)),
    collection_started_at timestamptz,
    -- 백필 시점 창 커버리지(V20260819125244, 수집 상한 v2 §7-1) — was는 둘 다 읽어 API로 노출한다.
    -- capped=true면 covered_until이 실수집 깊이, NULL이면 요청 창 전체 커버(운영 DDL과 동일 기본값).
    collection_capped     boolean     NOT NULL DEFAULT false,
    covered_until         timestamptz
);

CREATE TABLE IF NOT EXISTS brand_tagged_post (
    brand_id                 bigint      NOT NULL REFERENCES brand_account (id),
    short_code               text        NOT NULL,
    author_username          text        NOT NULL,
    author_ig_user_id        text,
    taken_at                 timestamptz NOT NULL,
    first_seen_at            timestamptz NOT NULL DEFAULT now(),
    comments_collected_count bigint      NOT NULL DEFAULT 0,
    -- 보강 정산 완료 시각(V20260813115041) — nullable·기본값 없음(운영 DDL과 동일).
    -- 기본값을 넣어 픽스처를 편하게 통과시키면 미러가 거짓말을 한다 — 값은 픽스처가 명시한다.
    enriched_at              timestamptz,
    -- 나이 티어 정책의 게시물별 마지막 수집 시각(V20260809120000) — was는 updatedAt(GREATEST) 산정에 읽는다.
    last_crawled_at          timestamptz,
    -- direct 통합 소스 분리 컬럼(V20260818040742) — was는 둘 다 읽는다(BrandReadRepository.findBrandPoolStatus).
    -- 기존 tagged 행은 전부 tag_detected_at이 채워져 있다는 운영 DDL 전제를 픽스처도 DEFAULT로 재현한다.
    tag_detected_at          timestamptz DEFAULT now(),
    direct_registered_at     timestamptz,
    -- 삭제·비공개 관측(V20260825044536) — 야간 스윕 단건 콜 404 시각, was는 hidden 판정에 읽는다.
    unavailable_at           timestamptz,
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
    image_archived_at   timestamptz,
    -- 광고 표기 판정 6컬럼(V20260817160000) — was는 ad_verdict·ad_violations·ad_evidence 3개만 읽는다.
    ad_verdict           text CHECK (ad_verdict IN
                              ('DISCLOSED', 'NOT_DISCLOSED', 'INSUFFICIENT', 'UNCERTAIN')),
    ad_verdict_source    text CHECK (ad_verdict_source IN ('RULE', 'LLM')),
    ad_violations        jsonb,
    ad_evidence          jsonb,
    ad_judged_at         timestamptz,
    judged_caption_hash  text
);

-- 시딩(협업) 계정 등록(V20260817160000) — was는 username만 브랜드 스코프로 읽는다.
CREATE TABLE IF NOT EXISTS brand_seeded_account (
    brand_id   bigint      NOT NULL REFERENCES brand_account (id),
    username   text        NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (brand_id, username)
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
    -- 작성자 프로필 사진 아카이브 3컬럼(V20260817142317) — was는 author_image_object_path만 읽는다.
    author_image_object_path   text,
    author_image_source_name   text,
    author_image_archived_at   timestamptz,
    PRIMARY KEY (brand_id, short_code)
);

-- 정본은 monitoring/src/main/resources/db/migration/V20260819054457__brand_hashtag_post_matched_tags.sql
-- (2026-08-19, was 사용자 스코프 필터 지원) — 게시물당 매칭된 태그 전체(matched_tag는 최초 저장
-- 태그 1개뿐, 이 테이블은 그 후 다른 태그가 같은 게시물을 다시 만나도 누적 기록한다).
CREATE TABLE IF NOT EXISTS brand_hashtag_post_matched_tags (
    brand_id   bigint      NOT NULL,
    short_code text        NOT NULL,
    tag        text        NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (brand_id, short_code, tag),
    FOREIGN KEY (brand_id, short_code) REFERENCES brand_hashtag_post (brand_id, short_code) ON DELETE CASCADE
);

-- 정본은 monitoring/src/main/resources/db/migration/V20260811085943__brand_hashtag_detection.sql +
-- V20260812120216__brand_hashtag_exclusion_soft_delete.sql(deleted_at tombstone, 2026-08-12).
-- 제외 문자열 기능은 2026-08-17 전면 폐기됐다(monitoring 관리 API 5종·was 프록시 API 4종·was
-- 조회 시점 필터 전부 제거) — 테이블 자체는 expand-contract 원칙상 아직 DROP하지 않았을 뿐,
-- was는 더 이상 이 테이블을 읽지 않는다.
CREATE TABLE IF NOT EXISTS brand_hashtag_exclusion (
    brand_id   bigint      NOT NULL REFERENCES brand_account (id),
    term       text        NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    deleted_at timestamptz,
    PRIMARY KEY (brand_id, term)
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

-- 캠페인·콘텐츠 모니터링 콜의 유저별 일별 집계(정본: monitoring V20260812160000__target_call_count.sql).
-- 브랜드 픽스처는 아니지만 크롤링 비용 카드가 brand_call_count와 함께 읽는 표면이라 같이 둔다.
CREATE TABLE IF NOT EXISTS target_call_count (
    user_id   bigint NOT NULL,
    called_on date   NOT NULL,
    calls     bigint NOT NULL DEFAULT 0,
    PRIMARY KEY (user_id, called_on)
);
