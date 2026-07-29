-- 조회 뷰 — was 캠페인 화면이 내부 테이블 구조에 직접 의존하지 않게 하는 계약 표면.

-- 캠페인 목록: target + 최신 프로필 스냅샷 + 승인 대기 후보 수
CREATE VIEW v_target_overview AS
SELECT t.id AS target_id, t.type, t.username, t.short_code, t.keyword_rule, t.status,
       t.tracked_short_code, t.tracked_since, t.expires_at, t.registered_at, t.closed_at,
       t.last_fetched_at, t.fail_reason,
       ps.captured_on AS profile_captured_on, ps.followers, ps.media_count,
       (SELECT count(*) FROM detected_candidate c
         WHERE c.target_id = t.id AND c.status = 'PENDING') AS pending_candidates
FROM target t
LEFT JOIN LATERAL (
    SELECT * FROM profile_snapshot p
    WHERE p.username = t.username ORDER BY p.captured_on DESC LIMIT 1
) ps ON true;

-- 추적 게시물 일별 추이 + 전일 대비 증감(파생 집계)
CREATE VIEW v_target_timeseries AS
SELECT t.id AS target_id, s.captured_on, s.content_type,
       s.likes, s.comments, s.views, s.saves, s.shares, s.reposts,
       s.likes    - lag(s.likes)    OVER w AS likes_delta,
       s.comments - lag(s.comments) OVER w AS comments_delta,
       s.views    - lag(s.views)    OVER w AS views_delta,
       s.saves    - lag(s.saves)    OVER w AS saves_delta,
       s.shares   - lag(s.shares)   OVER w AS shares_delta,
       s.reposts  - lag(s.reposts)  OVER w AS reposts_delta
FROM target t
JOIN post_snapshot s ON s.short_code = t.tracked_short_code
WINDOW w AS (PARTITION BY t.id ORDER BY s.captured_on);

-- was 읽기 전용 권한 — public 조회 표면만. raw 스키마는 GRANT 자체가 없어 접근 불가(fail-closed).
GRANT USAGE ON SCHEMA public TO was_reader;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO was_reader;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT ON TABLES TO was_reader;
