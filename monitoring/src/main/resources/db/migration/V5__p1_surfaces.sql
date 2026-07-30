-- P1 확장 4종(계약 v2.2 §3): post_meta·sweep_run·target 신호 컬럼(tracked_hidden_at·fetch_failing·
-- matched_keywords). v2.2부터 스윕은 target을 FAILED로 종결하지 않고 status는 유지한 채 hidden/error를
-- 신호로 세팅·복귀하는 체계로 개정됨에 따른 표면 확장이다.

-- 추적 대상 접근 불가(SUBJECT_NOT_FOUND/PRIVATE_ACCOUNT)·수집 오류(재시도 소진)·감지 자동 전환 시
-- 실제 매칭된 키워드 — 전부 status 유지한 채 세팅/복귀된다(계약 §3 target).
ALTER TABLE target ADD COLUMN tracked_hidden_at timestamptz;
ALTER TABLE target ADD COLUMN fetch_failing boolean NOT NULL DEFAULT false;
ALTER TABLE target ADD COLUMN matched_keywords jsonb;

-- 추적 게시물 표시 메타 — 게시물 단위 최신 1행(계약 §3 post_meta). profile_meta와 같은 upsert 관용구
-- (스냅샷과 달리 이력 없이 최신 1행, 수집이 게시물을 지나는 모든 경로에서 upsert).
CREATE TABLE post_meta (
    short_code    text        PRIMARY KEY,
    username      text        NOT NULL,
    content_type  text,
    uploaded_at   date        NOT NULL,
    caption       text        NOT NULL,
    thumbnail_url text,
    first_seen_at timestamptz NOT NULL DEFAULT now()
);

-- 일일 스윕 실행 대장 — 1실행 1행(계약 §3 sweep_run). was는 max(completed_at) WHERE ok로
-- meta.lastCollectedAt(마지막 성공 배치 완료 시각)을 읽는다.
CREATE TABLE sweep_run (
    id           bigserial   PRIMARY KEY,
    started_at   timestamptz NOT NULL,
    completed_at timestamptz,
    ok           boolean
);

-- 조회 뷰 개정 — target 구획에 tracked_hidden_at·fetch_failing·matched_keywords 3컬럼 추가
-- (계약 §3 v_target_overview, fail_reason 뒤에 삽입). CREATE OR REPLACE는 뷰 중간에 컬럼을 끼워
-- 넣는 걸 허용하지 않아(끝에 붙이는 것만 가능) DROP+CREATE로 간다.
-- (was_reader GRANT는 V2의 ALTER DEFAULT PRIVILEGES가 새 테이블에 자동 적용한다 — 별도 GRANT 문 불필요.)
DROP VIEW v_target_overview;

CREATE VIEW v_target_overview AS
SELECT t.id AS target_id, t.user_id, t.type, t.username, t.short_code, t.keyword_rule, t.status,
       t.tracked_short_code, t.tracked_since, t.registration_key, t.expires_at,
       t.registered_at, t.closed_at, t.last_fetched_at, t.fail_reason,
       t.tracked_hidden_at, t.fetch_failing, t.matched_keywords,
       ps.captured_on AS profile_captured_on, ps.followers, ps.media_count,
       -- 추적 게시물의 최신 지표 — 미추적(WATCHING) 캠페인은 전부 null.
       xs.captured_on AS post_captured_on, xs.content_type,
       xs.likes, xs.comments, xs.views, xs.saves, xs.shares, xs.reposts
FROM target t
LEFT JOIN LATERAL (
    -- 컬럼은 명시 — SELECT *면 스냅샷 테이블에 컬럼이 늘 때 뷰 계약이 조용히 바뀐다.
    SELECT p.captured_on, p.followers, p.media_count FROM profile_snapshot p
    WHERE p.username = t.username ORDER BY p.captured_on DESC LIMIT 1
) ps ON true
LEFT JOIN LATERAL (
    SELECT s.captured_on, s.content_type, s.likes, s.comments, s.views,
           s.saves, s.shares, s.reposts
    FROM post_snapshot s
    WHERE s.short_code = t.tracked_short_code ORDER BY s.captured_on DESC LIMIT 1
) xs ON true;
