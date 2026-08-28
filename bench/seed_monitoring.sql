-- 벤치 목 데이터 시드 — monitoring DB. bench/seed.sh가 실행한다.
-- ⚠️ 벤치 전용 컨테이너 전제 — sweep_run은 전삭제한다. 실데이터 DB에 돌리지 말 것.
-- 조립 경로가 실제로 끝까지 돌기 위한 계약(TrackingItemAssembler 실측):
--   · tracked_short_code NULL이면 post 조립 자체가 스킵 → WATCHING만 NULL로 둔다
--   · sweep_run에 ok=true·completed_at 행이 없으면 스냅샷이 전부 빈 배열
--   · profile_snapshot.followers NULL이면 Collectors.toMap NPE → 항상 채운다
--   · FEED 게시물 views는 NULL(운영 불변식)
\set ON_ERROR_STOP on
BEGIN;

DELETE FROM post_snapshot WHERE short_code LIKE 'BENCH%';
DELETE FROM post_comment WHERE short_code LIKE 'BENCH%';
DELETE FROM post_meta WHERE short_code LIKE 'BENCH%';
DELETE FROM profile_snapshot WHERE username LIKE 'bench_ig_%';
DELETE FROM profile_meta WHERE username LIKE 'bench_ig_%';
DELETE FROM target WHERE id BETWEEN 1000000 AND 9999999;
DELETE FROM sweep_run;

-- target — seed_app.sql의 아이템과 1000000 + u*1000 + i 공식으로 1:1.
-- type은 mode와 짝(account↔ACCOUNT), status는 운영 비율 근사(TRACKING 60/WATCHING 20/CANCELED 10/EXPIRED 10)
WITH g AS (
    SELECT u, i, u * 1000 + i AS n,
           CASE WHEN i % 3 = 0 THEN 'ACCOUNT' ELSE 'POST' END AS typ,
           CASE WHEN i % 10 < 6 THEN 'TRACKING' WHEN i % 10 < 8 THEN 'WATCHING'
                WHEN i % 10 = 8 THEN 'CANCELED' ELSE 'EXPIRED' END AS st
    FROM generate_series(0, :n_users) u
    CROSS JOIN LATERAL generate_series(1, CASE WHEN u = 0 THEN :bench_items ELSE :items_per_user END) i
)
INSERT INTO target (id, type, username, short_code, keyword_rule, status, tracked_short_code,
                    tracked_since, registration_key, expires_at, registered_at, closed_at,
                    last_fetched_at, user_id, fetch_failing, matched_keywords)
SELECT 1000000 + n, typ, 'bench_ig_' || n,
       CASE WHEN typ = 'POST' THEN 'BENCH' || lpad(n::text, 8, '0') END,
       CASE WHEN typ = 'ACCOUNT' THEN '{"and":[],"or":["벤치"],"exclude":[]}'::jsonb END,
       st,
       CASE WHEN st <> 'WATCHING' THEN 'BENCH' || lpad(n::text, 8, '0') END,
       CASE WHEN typ = 'ACCOUNT' AND st <> 'WATCHING'
            THEN now() - make_interval(days => :snapshot_days) END,
       'bench-rk-' || n,
       now() + interval '30 days',
       now() - make_interval(days => :snapshot_days + 1),
       CASE WHEN st IN ('CANCELED', 'EXPIRED') THEN now() END,
       now(),
       900000 + u,
       false,
       CASE WHEN typ = 'ACCOUNT' AND st <> 'WATCHING' THEN '["벤치"]'::jsonb END
FROM g;

-- 추적 게시물 메타 — REELS 60% / FEED 40%
INSERT INTO post_meta (short_code, username, content_type, uploaded_at, caption, thumbnail_url)
SELECT t.tracked_short_code, t.username,
       CASE WHEN t.id % 5 < 3 THEN 'REELS' ELSE 'FEED' END,
       current_date - :snapshot_days,
       '벤치 캡션 ' || t.id,
       'https://example.invalid/thumb/' || t.id || '.jpg'
FROM target t
WHERE t.id BETWEEN 1000000 AND 9999999 AND t.tracked_short_code IS NOT NULL;

-- 일별 지표 스냅샷
INSERT INTO post_snapshot (username, short_code, captured_on, content_type, likes, comments,
                           views, saves, shares, reposts, likes_hidden, shares_hidden)
SELECT t.username, t.tracked_short_code, current_date - d,
       CASE WHEN t.id % 5 < 3 THEN 'REELS' ELSE 'FEED' END,
       100 + (t.id * 37 + d * 13) % 5000,
       (t.id * 11 + d * 7) % 300,
       CASE WHEN t.id % 5 < 3 THEN 1000 + (t.id * 97 + d * 31) % 100000 END,
       (t.id * 5 + d) % 500,
       CASE WHEN t.id % 5 < 3 THEN (t.id * 3 + d) % 200 END,
       CASE WHEN t.id % 5 < 3 THEN (t.id + d) % 50 END,
       false, false
FROM target t, generate_series(0, :snapshot_days - 1) d
WHERE t.id BETWEEN 1000000 AND 9999999 AND t.tracked_short_code IS NOT NULL;

-- 게시물당 댓글 C개(응답에는 게시물당 최신 45개만 실린다 — 그 컷 비용도 측정 대상)
INSERT INTO post_comment (short_code, id, author, body, like_count, commented_at)
SELECT t.tracked_short_code, 'bench-c' || c, 'bench_commenter_' || (c % 20),
       '벤치 댓글 본문 ' || c, (c * 7) % 50, now() - make_interval(hours => c)
FROM target t, generate_series(1, :comments_per_post) c
WHERE t.id BETWEEN 1000000 AND 9999999 AND t.tracked_short_code IS NOT NULL;

INSERT INTO profile_meta (username, display_name, profile_image_url, last_uploaded_at, updated_at)
SELECT t.username, '벤치계정 ' || t.id, 'https://example.invalid/p/' || t.id || '.jpg',
       current_date, now()
FROM target t WHERE t.id BETWEEN 1000000 AND 9999999;

INSERT INTO profile_snapshot (username, captured_on, followers, following, media_count)
SELECT t.username, current_date - d, 1000 + t.id % 100000 + d * 3, 500, 100
FROM target t, generate_series(0, :snapshot_days - 1) d
WHERE t.id BETWEEN 1000000 AND 9999999;

-- 스윕 이력 — 최신 행(s=0)이 ok=true·completed_at=now()라 스냅샷 상한을 항상 통과
INSERT INTO sweep_run (started_at, completed_at, ok)
SELECT now() - make_interval(mins => 30 * s + 10), now() - make_interval(mins => 30 * s), (s % 7 <> 1)
FROM generate_series(0, 29) s;

COMMIT;

ANALYZE target;
ANALYZE post_meta;
ANALYZE post_snapshot;
ANALYZE post_comment;
ANALYZE profile_meta;
ANALYZE profile_snapshot;
ANALYZE sweep_run;
