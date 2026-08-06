-- 브랜드 태그 모니터링(2026-08-06 스펙 + 같은 날 설계 논의 개정) — expand 단계 신규 7테이블.
--
-- 전면 브랜드 전용 스키마(사용자 결정 08-06): 캠페인 모니터링 테이블(post_snapshot·post_meta·
-- post_comment·profile_meta)을 브랜드 파이프라인이 한 줄도 건드리지 않는다 — 볼륨 비대칭
-- (매일 전량 수집으로 연 ~7,700만 행)과 겹침 게시물 덮어쓰기(캠페인 재시도로 채운 복권 지표를
-- 브랜드 꽝 세션이 null로 되돌리는 버그)를 구조적으로 차단한다. 겹침 게시물 댓글 이중 수집은
-- 수용(게시물당 최대 3콜 중복 — 무시 가능한 비용).
--
-- 수집 주기도 개정: 감지/트래킹 구분 폐지 — 매일 전 브랜드를 105개 깊이로 열거하고 윈도우 안
-- 전 게시물이 매일 1행 스냅샷을 갖는다(기존 캠페인 모듈과 같은 일 단위 시계열).
-- was_reader SELECT는 V2의 ALTER DEFAULT PRIVILEGES가 자동 적용(별도 GRANT 불필요).

-- 브랜드 계정 — 가입 시 자동 시작, 탈퇴(CLOSED)까지 추적(휴면 완화 없음 — 스펙 §8).
-- followers·biography는 매일 스윕이 갱신하는 최신값(개정 08-06 — 등록 1회가 아님, 추이는
-- brand_profile_snapshot에).
CREATE TABLE brand_account (
    id            bigserial   PRIMARY KEY,
    username      text        NOT NULL UNIQUE,
    ig_user_id    text        NOT NULL,   -- 태그 열거의 user_id 파라미터(등록 프로필 콜에서 해석)
    followers     bigint,
    biography     text,
    status        text        NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'CLOSED')),
    registered_at timestamptz NOT NULL DEFAULT now(),
    closed_at     timestamptz,
    -- 마지막 전량 수집(백필 포함) 완주일. null = 백필 미완(등록 직후 비동기 백필 실패 포함) —
    -- 다음 스윕이 자연 백스톱하고, 후속 was 계약이 "수집 준비 중" 판별에 쓴다(별도 상태 컬럼 없음).
    last_swept_on date
);

-- 브랜드별 확보 태그 게시물 — "이 브랜드 윈도우에 이 게시물이 있다"는 링크와 댓글 게이트 상태.
-- 게시물 자체의 지표·메타·댓글은 아래 게시물 전역 테이블에 있다(여러 브랜드가 같은 게시물을
-- 태그해도 관측은 1벌).
CREATE TABLE brand_tagged_post (
    brand_id                 bigint      NOT NULL REFERENCES brand_account (id),
    short_code               text        NOT NULL,
    author_username          text        NOT NULL,
    author_ig_user_id        text,                    -- 열거 user.pk(셰이프에 따라 null 가능)
    taken_at                 timestamptz NOT NULL,    -- 90일 윈도우 컷 판정 기준(열거 taken_at)
    first_seen_at            timestamptz NOT NULL DEFAULT now(),
    -- 댓글 게이팅 저장값(스펙 §2) — 마지막 댓글 수집 시점의 열거 comment_count.
    -- 열거값이 이보다 클 때만 댓글 콜을 낸다(신규 게시물은 0이라 "댓글 1개 이상만 수집"이 자동 성립).
    comments_collected_count bigint      NOT NULL DEFAULT 0,
    PRIMARY KEY (brand_id, short_code)
);

-- 태그 게시물 지표 시계열 — 게시물 전역 하루 1행(브랜드 수와 무관). 컬럼은 post_snapshot과
-- 동형(캐리포워드·0 캐리 규칙을 동형 이식하기 위한 전제) — 윈도우 이탈 후에도 영구 보존(08-06).
CREATE TABLE brand_post_snapshot (
    username      text   NOT NULL,   -- 게시물 작성자(인플루언서)
    short_code    text   NOT NULL,
    captured_on   date   NOT NULL,
    content_type  text,              -- REELS / FEED
    likes         bigint,
    likes_hidden  boolean NOT NULL DEFAULT false,
    comments      bigint,
    views         bigint,            -- 화면 합산값(IG 몫 + FB 몫) — post_snapshot과 동일 규칙
    fb_plays      bigint,
    saves         bigint,
    shares        bigint,
    shares_hidden boolean NOT NULL DEFAULT false,
    reposts       bigint,
    PRIMARY KEY (short_code, captured_on)
);
CREATE INDEX idx_brand_post_snapshot_username ON brand_post_snapshot (username, captured_on);

-- 태그 게시물 표시 메타 — 게시물 전역 최신 1행(이력 없이 upsert, post_meta 동형).
-- 썸네일 CDN 서명(~4일 만료)은 매일 스윕 upsert가 자동 방어한다.
CREATE TABLE brand_post_meta (
    short_code    text        PRIMARY KEY,
    username      text        NOT NULL,   -- 게시물 작성자
    content_type  text,
    uploaded_at   date        NOT NULL,
    caption       text        NOT NULL,
    thumbnail_url text,
    first_seen_at timestamptz NOT NULL DEFAULT now()
);

-- 태그 게시물 댓글 — 게시물 전역 누적 합집합(post_comment 동형 — 행 삭제 없음).
CREATE TABLE brand_post_comment (
    short_code       text        NOT NULL,
    id               text        NOT NULL,
    author           text        NOT NULL,
    body             text        NOT NULL,
    like_count       bigint      NOT NULL,
    commented_at     timestamptz NOT NULL,
    owner_reply_text text,
    PRIMARY KEY (short_code, id)
);

-- 브랜드 계정 프로필 추이 — 매일 스윕의 프로필 1콜을 일 1행으로 적재(profile_snapshot 동형).
-- 최신값은 brand_account.followers·biography가 들고, 여기는 추이 시계열이다.
CREATE TABLE brand_profile_snapshot (
    username    text   NOT NULL,
    captured_on date   NOT NULL,
    followers   bigint,
    following   bigint,
    media_count bigint,
    PRIMARY KEY (username, captured_on)
);

-- 게시자(인플루언서) 프로필 — 브랜드 간 전역 캐시(스펙 §6), 이력 없이 최신 1행(08-06 확정).
-- fetched_at 30일 경과 + 등장 시 재조회(월 일괄 배치 아님 — 스펙 §8).
CREATE TABLE author_profile (
    ig_user_id      text        PRIMARY KEY,
    username        text        NOT NULL,
    full_name       text,
    followers       bigint,
    following       bigint,
    media_count     bigint,
    biography       text,
    profile_pic_url text,
    is_private      boolean,    -- 게시자 비공개는 오류가 아니라 관측값(브랜드 계정과 다름)
    fetched_at      timestamptz NOT NULL
);
