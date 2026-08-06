-- 브랜드 태그 모니터링(2026-08-06 스펙) — expand 단계 신규 3테이블.
-- 스냅샷·게시물 메타·댓글·게시자 표시 메타는 기존 post_snapshot·post_meta·post_comment·
-- profile_meta를 재사용한다(SnapshotWriter.savePost 단일 깔때기) — 여기엔 브랜드 도메인만 담는다.
-- was_reader SELECT는 V2의 ALTER DEFAULT PRIVILEGES가 자동 적용(별도 GRANT 불필요).

-- 브랜드 계정 — 가입 시 자동 시작, 탈퇴(CLOSED)까지 추적(휴면 완화 없음 — 스펙 §8).
-- followers·biography는 등록 시 1콜의 관측값(스펙 §2 — 상시 갱신 대상 아님).
CREATE TABLE brand_account (
    id              bigserial   PRIMARY KEY,
    username        text        NOT NULL UNIQUE,
    ig_user_id      text        NOT NULL,   -- 태그 열거의 user_id 파라미터(등록 프로필 콜에서 해석)
    followers       bigint,
    biography       text,
    status          text        NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'CLOSED')),
    registered_at   timestamptz NOT NULL DEFAULT now(),
    closed_at       timestamptz,
    -- 3일 트래킹 주기 판정 기준. null = 백필 미완(등록 직후 비동기 백필 실패 포함) —
    -- 다음 스윕이 트래킹으로 백스톱한다(감지/트래킹 판정은 BrandSweepJob).
    last_tracked_on date
);

-- 브랜드별 확보 태그 게시물 — 지표·메타·댓글은 기존 공용 테이블에 있고, 여기는
-- "이 브랜드 윈도우에 이 게시물이 있다"는 링크와 댓글 게이트 상태만 담는다.
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

-- 게시자(인플루언서) 프로필 — 브랜드 간 전역 캐시(스펙 §6). 이력 없이 최신 1행 upsert,
-- fetched_at 30일 경과 + 트래킹 등장 시 재조회(월 일괄 배치 아님 — 스펙 §8).
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
