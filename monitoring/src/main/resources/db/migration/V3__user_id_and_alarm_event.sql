-- 알람 소유가 monitoring으로 이동(스펙 2026-07-30) — 수신자 해석에 필요한 user_id를 캠페인에 싣고,
-- 알람 이벤트 대장을 신설한다. 승인 플로우 제거로 v_target_overview의 후보 수 컬럼은 의미를 잃었다.

-- was 유저의 논리 참조 (크로스 DB — FK 금지, saved_influencers.handle 관용구).
-- nullable(expand 단계): 기존 운영 행은 null로 남고 알람 적재에서 제외된다(수신자 불명).
-- SET NOT NULL 승격은 백필 런북 실행 후 다음 릴리스에서 판단한다.
ALTER TABLE target ADD COLUMN user_id bigint;

-- 지표 비공개 알람이 "이 게시물을 추적 중인 캠페인"을 역방향으로 찾는다 — 스윕이 게시물마다 도는 조회라
-- 인덱스가 없으면 계정당 12건 × 캠페인 전수 스캔이 된다.
CREATE INDEX idx_target_tracked_short_code ON target (tracked_short_code)
    WHERE tracked_short_code IS NOT NULL;

-- 알람 이벤트 대장 — 발송 여부와 무관하게 모든 이벤트가 남는다(앱 내 알림·히스토리의 단일 원천).
-- id 기반이라 워터마크가 없다: 발송 실패는 행 단위 FAILED로 남고 다음 틱이 그 행만 다시 집는다.
CREATE TABLE alarm_event (
    id             bigserial   PRIMARY KEY,
    target_id      bigint      NOT NULL,   -- 논리 참조(target 행은 불멸이라 조인 안전 — FK를 걸지 않는 건 대장 보존이 목적)
    user_id        bigint      NOT NULL,   -- 수신자 (was 유저 논리 참조)
    event_type     text        NOT NULL CHECK (event_type IN
                   ('COLLECTION_STARTED','COLLECTION_ENDED','METRICS_HIDDEN','CONTENT_UNAVAILABLE')),
    payload        jsonb       NOT NULL,   -- 문안 재료: username·shortCode·상세(숨은 지표 목록 등)
    occurred_at    timestamptz NOT NULL DEFAULT now(),
    dispatch_after timestamptz NOT NULL,   -- 발송 레인: 즉시(=occurred_at) 또는 적재 당일 09:00 KST
    -- SKIPPED_NO_RECIPIENT: 유저 삭제·이메일 부재 — 재시도가 무의미한 종결(FAILED로 두면 매 틱 재시도한다)
    email_status   text        NOT NULL DEFAULT 'PENDING' CHECK (email_status IN
                   ('PENDING','SENT','SKIPPED_OPTOUT','SKIPPED_NO_RECIPIENT','FAILED')),
    -- 발송 시도 횟수(성공·실패 무관). FAILED는 다음 틱에 다시 집히므로 상한이 없으면 영구 실패
    -- 수신자 하나가 5분마다 무한히 Resend를 때린다 — 상한(기본 5) 도달 행은 due 조회에서 자연히 빠진다.
    email_attempts smallint    NOT NULL DEFAULT 0,
    email_sent_at  timestamptz
);

-- 발송 대상 부분 인덱스 — FAILED도 다음 틱 재시도 대상이라 함께 담는다.
-- PENDING만 담으면 FAILED 재시도 조회가 이 인덱스를 못 타고 전수 스캔이 된다.
CREATE INDEX alarm_event_pending_idx ON alarm_event (dispatch_after)
    WHERE email_status IN ('PENDING', 'FAILED');
-- 앱 내 알림·히스토리 서빙용(was 읽기 전용 SELECT)
CREATE INDEX alarm_event_user_idx ON alarm_event (user_id, occurred_at DESC);

-- 조회 뷰 개정 — user_id 노출(was의 소유 스코프 조회용), pending_candidates 제거(승인 플로우 폐지).
-- CREATE OR REPLACE는 컬럼 삭제를 허용하지 않아 DROP+CREATE로 간다.
-- (was_reader GRANT는 V2의 ALTER DEFAULT PRIVILEGES가 새 뷰·새 테이블에 자동 적용한다 —
--  alarm_event·재생성 뷰 모두 같은 소유자(monitoring)가 만들므로 별도 GRANT 문이 필요 없다.)
DROP VIEW v_target_overview;

CREATE VIEW v_target_overview AS
SELECT t.id AS target_id, t.user_id, t.type, t.username, t.short_code, t.keyword_rule, t.status,
       t.tracked_short_code, t.tracked_since, t.registration_key, t.expires_at,
       t.registered_at, t.closed_at, t.last_fetched_at, t.fail_reason,
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
