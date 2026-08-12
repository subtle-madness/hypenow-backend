-- 브랜드 해시태그 감지(2026-08-11 스펙) — expand 단계 신규 3테이블.
-- 해시태그-only 언급 게시물의 발견·판정만 저장한다(보강·스냅샷 없음 — 스펙 §5 보류).
-- 기존 브랜드 7테이블·캠페인 테이블 비접촉(08-06 격리 원칙).

-- 브랜드별 스윕 대상 해시태그 — 등록 시 자동 유도 3종(#브랜드명·#루트·#전체계정명) 유니온 삽입.
CREATE TABLE brand_hashtag (
    brand_id   bigint      NOT NULL REFERENCES brand_account (id),
    tag        text        NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (brand_id, tag)
);

-- 자사 계열 제외 문자열 — 게시자 username에 포함되면 SELF 판정. 기본값은 계정명 루트,
-- 유저가 was 경유로 관리(전체 교체 PUT).
CREATE TABLE brand_hashtag_exclusion (
    brand_id   bigint      NOT NULL REFERENCES brand_account (id),
    term       text        NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (brand_id, term)
);

-- 필터 도달 게시물 전량 저장(SELF·DIRECT_TAGGED·무관·불확실 포함) — 이 테이블이 조기 종료의
-- "기존 게시물" 판정 재료이자 dedup 키이자 재판정 재료다. 윈도우(90일) 밖은 미저장.
-- 피드 노출은 verdict='RELEVANT'만(스펙 §5).
CREATE TABLE brand_hashtag_post (
    brand_id               bigint      NOT NULL REFERENCES brand_account (id),
    short_code             text        NOT NULL,
    matched_tag            text        NOT NULL,   -- 발견 경로 태그(운영 디버그용)
    author_username        text        NOT NULL,
    author_full_name       text,
    author_profile_pic_url text,
    taken_at               timestamptz NOT NULL,
    caption                text        NOT NULL DEFAULT '',
    content_type           text,                   -- REELS / FEED (열거 관측)
    thumbnail_url          text,
    likes                  bigint,
    comments               bigint,
    verdict                text        NOT NULL CHECK (verdict IN
                               ('RELEVANT', 'UNCERTAIN', 'IRRELEVANT', 'SELF', 'DIRECT_TAGGED')),
    verdict_source         text        NOT NULL CHECK (verdict_source IN ('RULE', 'MENTION', 'LLM')),
    first_seen_at          timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (brand_id, short_code)
);

-- was 피드 조회(RELEVANT만, 최신순) 전용 부분 인덱스.
CREATE INDEX brand_hashtag_post_feed_idx
    ON brand_hashtag_post (brand_id, taken_at DESC) WHERE verdict = 'RELEVANT';
