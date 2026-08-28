-- 벤치 목 데이터 시드 — app 스키마(analysis DB). bench/seed.sh가 실행한다.
-- id 대역: users 900000+, campaigns 800000+, items 1000000+ (bench 전용 — 삭제 기준).
-- u=0이 벤치 유저(bench@bench.local, 아이템 :bench_items개), u=1..:n_users는 배경 유저.
-- monitoring DB의 target.id와 아이템 target_id는 1000000 + u*1000 + i 공식으로 맞물린다
-- (seed_monitoring.sql과 공식·mode↔type 매핑이 계약 — 한쪽만 바꾸면 조립이 빈다).
\set ON_ERROR_STOP on
BEGIN;

-- 캠페인·아이템은 users FK ON DELETE CASCADE로 함께 삭제된다
DELETE FROM app.users WHERE id BETWEEN 900000 AND 909999;

INSERT INTO app.users (id, email, password_hash, name)
SELECT 900000 + u,
       CASE WHEN u = 0 THEN 'bench@bench.local' ELSE 'bench' || u || '@bench.local' END,
       :'bench_hash',
       'bench_user_' || u
FROM generate_series(0, :n_users) u;

INSERT INTO app.monitoring_campaigns (id, user_id, name, brand, budget, seeding_count,
                                      start_date, end_date, created_at)
SELECT 800000 + u * 100 + m, 900000 + u, '벤치 캠페인 ' || m, '벤치브랜드', 1000000, 10,
       current_date - 30, current_date + 30, now() - make_interval(mins => m)
FROM generate_series(0, :n_users) u, generate_series(1, :campaigns_per_user) m;

-- mode: i%3=0 → account(≈33%, 운영 ACCOUNT 31% 근사), 그 외 url.
-- registered_on은 스냅샷 최초일보다 과거여야 한다(조립 하한 필터) — D+1일 전으로.
INSERT INTO app.monitoring_items (id, user_id, mode, registration_key, target_id, campaign_id,
                                  input_value, source_url, keywords, tracking_days,
                                  registered_on, created_at)
SELECT 1000000 + u * 1000 + i,
       900000 + u,
       CASE WHEN i % 3 = 0 THEN 'account' ELSE 'url' END,
       gen_random_uuid(),
       1000000 + u * 1000 + i,
       800000 + u * 100 + 1 + (i % :campaigns_per_user),
       CASE WHEN i % 3 = 0 THEN 'bench_ig_' || (u * 1000 + i)
            ELSE 'BENCH' || lpad((u * 1000 + i)::text, 8, '0') END,
       CASE WHEN i % 3 <> 0
            THEN 'https://www.instagram.com/p/BENCH' || lpad((u * 1000 + i)::text, 8, '0') || '/' END,
       CASE WHEN i % 3 = 0 THEN '{"and":[],"or":["벤치"],"exclude":[]}'::jsonb END,
       90,
       current_date - :snapshot_days - 1,
       now()
FROM generate_series(0, :n_users) u
CROSS JOIN LATERAL generate_series(1, CASE WHEN u = 0 THEN :bench_items ELSE :items_per_user END) i;

COMMIT;

ANALYZE app.users;
ANALYZE app.monitoring_campaigns;
ANALYZE app.monitoring_items;
