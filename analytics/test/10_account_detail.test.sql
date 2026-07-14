-- 그룹 10 기대값. 산식 정본: celfit-front parse_accounts_recent.py (스펙 §3).
-- 결정성: 이 그룹이 읽는 설정 키를 기본값으로 강제.
DELETE FROM app_setting WHERE key IN ('analytics.recent-window', 'analytics.trend-threshold');

-- ===== 추가 픽스처: metric 'views'·트렌드 down/flat·광고 비교 검증용 (계정 9005~9006) =====
INSERT INTO account(id, username) VALUES (9005,'dummy_v'), (9006,'dummy_flat');
INSERT INTO raw_profile(account_id, crawl_run_id, payload, captured_at) VALUES
 (9005,9990,'{"username":"dummy_v","followersCount":10000,"followsCount":300,"postsCount":80,"biography":"글로우 크리에이터"}'::jsonb, timestamptz '2026-06-06 00:00:00+09'),
 (9006,9990,'{"username":"dummy_flat","followersCount":8000}'::jsonb, timestamptz '2026-06-06 00:00:00+09');

INSERT INTO content(id, short_code, content_type, owner_username, uploaded_at, category_id, discovery_keyword, status, first_seen_at, subcategory, main_group, ad_marked) VALUES
 (9110,'dummy_v1','REELS','dummy_v',    timestamptz '2026-05-01 09:00:00+09',999,'glow','AGGREGATED', timestamptz '2026-05-01 00:00:00+09','glow_sub','B', false),
 (9111,'dummy_v2','REELS','dummy_v',    timestamptz '2026-05-08 09:00:00+09',999,'glow','AGGREGATED', timestamptz '2026-05-08 00:00:00+09','glow_sub','B', false),
 (9112,'dummy_v3','REELS','dummy_v',    timestamptz '2026-05-15 09:00:00+09',999,'glow','AGGREGATED', timestamptz '2026-05-15 00:00:00+09','glow_sub','B', true),
 (9113,'dummy_v4','REELS','dummy_v',    timestamptz '2026-05-22 09:00:00+09',999,'glow','AGGREGATED', timestamptz '2026-05-22 00:00:00+09','glow_sub','B', false),
 (9114,'dummy_v5','REELS','dummy_v',    timestamptz '2026-05-29 09:00:00+09',999,'glow','AGGREGATED', timestamptz '2026-05-29 00:00:00+09','glow_sub','B', true),
 (9115,'dummy_v6','REELS','dummy_v',    timestamptz '2026-06-05 09:00:00+09',999,'glow','AGGREGATED', timestamptz '2026-06-05 00:00:00+09','glow_sub','B', false),
 (9120,'dummy_t1','REELS','dummy_flat', timestamptz '2026-05-01 09:00:00+09',999,'glow','AGGREGATED', timestamptz '2026-05-01 00:00:00+09','glow_sub','B', false),
 (9121,'dummy_t2','REELS','dummy_flat', timestamptz '2026-05-08 09:00:00+09',999,'glow','AGGREGATED', timestamptz '2026-05-08 00:00:00+09','glow_sub','B', false),
 (9122,'dummy_t3','REELS','dummy_flat', timestamptz '2026-05-15 09:00:00+09',999,'glow','AGGREGATED', timestamptz '2026-05-15 00:00:00+09','glow_sub','B', false),
 (9123,'dummy_t4','REELS','dummy_flat', timestamptz '2026-05-22 09:00:00+09',999,'glow','AGGREGATED', timestamptz '2026-05-22 00:00:00+09','glow_sub','B', false);

INSERT INTO raw_post_detail(content_id, crawl_run_id, payload, captured_at) VALUES
 (9110,9990,'{"shortCode":"dummy_v1","type":"Video","likesCount":400,"commentsCount":40,"videoPlayCount":20000}'::jsonb, timestamptz '2026-05-02 09:00:00+09'),
 (9111,9990,'{"shortCode":"dummy_v2","type":"Video","likesCount":300,"commentsCount":30,"videoPlayCount":18000}'::jsonb, timestamptz '2026-05-09 09:00:00+09'),
 (9112,9990,'{"shortCode":"dummy_v3","type":"Video","likesCount":500,"commentsCount":50,"videoPlayCount":22000}'::jsonb, timestamptz '2026-05-16 09:00:00+09'),
 (9113,9990,'{"shortCode":"dummy_v4","type":"Video","likesCount":200,"commentsCount":20,"videoPlayCount":10000}'::jsonb, timestamptz '2026-05-23 09:00:00+09'),
 (9114,9990,'{"shortCode":"dummy_v5","type":"Video","likesCount":150,"commentsCount":15,"videoPlayCount":8000}'::jsonb,  timestamptz '2026-05-30 09:00:00+09'),
 (9115,9990,'{"shortCode":"dummy_v6","type":"Video","likesCount":100,"commentsCount":10,"videoPlayCount":6000}'::jsonb,  timestamptz '2026-06-06 09:00:00+09'),
 (9120,9990,'{"shortCode":"dummy_t1","type":"Video","likesCount":200,"commentsCount":20,"videoPlayCount":10000}'::jsonb, timestamptz '2026-05-02 09:00:00+09'),
 (9121,9990,'{"shortCode":"dummy_t2","type":"Video","likesCount":210,"commentsCount":21,"videoPlayCount":11000}'::jsonb, timestamptz '2026-05-09 09:00:00+09'),
 (9122,9990,'{"shortCode":"dummy_t3","type":"Video","likesCount":190,"commentsCount":19,"videoPlayCount":9000}'::jsonb,  timestamptz '2026-05-16 09:00:00+09'),
 (9123,9990,'{"shortCode":"dummy_t4","type":"Video","likesCount":205,"commentsCount":25,"videoPlayCount":11500}'::jsonb, timestamptz '2026-05-23 09:00:00+09');

-- ===== v_account_summaries =====
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM analytics.v_account_summaries WHERE handle LIKE 'dummy_%') = 4,
    'summaries rows != 4';
END $$;

-- dummy_a: metric 'likes' 폴백 + 피드 NULL 함정 + 광고 비교 + 트렌드 up
DO $$
BEGIN
  ASSERT (SELECT followers          FROM analytics.v_account_summaries WHERE handle='dummy_a') = 5500,  'a followers != 5500';
  ASSERT (SELECT follows_count      FROM analytics.v_account_summaries WHERE handle='dummy_a') IS NULL, 'a follows_count not null';
  ASSERT (SELECT posts_count        FROM analytics.v_account_summaries WHERE handle='dummy_a') IS NULL, 'a posts_count not null';
  ASSERT (SELECT biography          FROM analytics.v_account_summaries WHERE handle='dummy_a') IS NULL, 'a biography not null';
  ASSERT (SELECT analyzed_count     FROM analytics.v_account_summaries WHERE handle='dummy_a') = 3,     'a analyzed != 3';
  ASSERT (SELECT views_count        FROM analytics.v_account_summaries WHERE handle='dummy_a') = 2,     'a views_count != 2';
  ASSERT (SELECT metric             FROM analytics.v_account_summaries WHERE handle='dummy_a') = 'likes', 'a metric != likes (2 < max(3, 3/2))';
  ASSERT (SELECT avg_views          FROM analytics.v_account_summaries WHERE handle='dummy_a') = 9000,  'a avg_views != 9000';
  ASSERT (SELECT views_per_follower FROM analytics.v_account_summaries WHERE handle='dummy_a') = 1.6,   'a vpf != 1.6';
  ASSERT (SELECT avg_er_pct         FROM analytics.v_account_summaries WHERE handle='dummy_a') = 18.2,  'a avg_er_pct != 18.2';
  ASSERT (SELECT avg_likes          FROM analytics.v_account_summaries WHERE handle='dummy_a') = 940,   'a avg_likes != 940';
  ASSERT (SELECT avg_comments       FROM analytics.v_account_summaries WHERE handle='dummy_a') = 61,    'a avg_comments != 61';
  ASSERT (SELECT trend_direction    FROM analytics.v_account_summaries WHERE handle='dummy_a') = 'up',  'a trend != up';
  ASSERT (SELECT trend_change_pct   FROM analytics.v_account_summaries WHERE handle='dummy_a') = 121,   'a trend_pct != 121';
  ASSERT (SELECT trend_older_avg    FROM analytics.v_account_summaries WHERE handle='dummy_a') = 520,   'a older != 520';
  ASSERT (SELECT trend_newer_avg    FROM analytics.v_account_summaries WHERE handle='dummy_a') = 1150,  'a newer != 1150';
  ASSERT (SELECT sponsored_count    FROM analytics.v_account_summaries WHERE handle='dummy_a') = 1,     'a sponsored != 1';
  ASSERT (SELECT organic_avg        FROM analytics.v_account_summaries WHERE handle='dummy_a') = 410,   'a organic_avg != 410';
  ASSERT (SELECT ad_avg             FROM analytics.v_account_summaries WHERE handle='dummy_a') = 2000,  'a ad_avg != 2000';
  ASSERT (SELECT ad_drop_pct        FROM analytics.v_account_summaries WHERE handle='dummy_a') = -388,  'a drop != -388';
  ASSERT (SELECT comparison_organic_count FROM analytics.v_account_summaries WHERE handle='dummy_a') = 2, 'a cmp_og != 2';
  ASSERT (SELECT comparison_ad_count      FROM analytics.v_account_summaries WHERE handle='dummy_a') = 1, 'a cmp_ad != 1';
  ASSERT (SELECT last_ad_posted_at  FROM analytics.v_account_summaries WHERE handle='dummy_a') = timestamptz '2026-06-03 09:00:00+09', 'a last_ad wrong';
  ASSERT (SELECT last_posted_at     FROM analytics.v_account_summaries WHERE handle='dummy_a') = timestamptz '2026-06-03 09:00:00+09', 'a last_posted wrong';
  ASSERT (SELECT avg_interval_days  FROM analytics.v_account_summaries WHERE handle='dummy_a') = 1.0,   'a interval != 1.0';
END $$;

-- dummy_b: 표본 1 — 트렌드 flat(앞 절반 없음)·interval NULL·광고 없음
DO $$
BEGIN
  ASSERT (SELECT metric             FROM analytics.v_account_summaries WHERE handle='dummy_b') = 'likes', 'b metric != likes (1 < 3)';
  ASSERT (SELECT avg_views          FROM analytics.v_account_summaries WHERE handle='dummy_b') = 40000,  'b avg_views != 40000';
  ASSERT (SELECT views_per_follower FROM analytics.v_account_summaries WHERE handle='dummy_b') = 2.0,    'b vpf != 2.0';
  ASSERT (SELECT avg_er_pct         FROM analytics.v_account_summaries WHERE handle='dummy_b') = 5.4,    'b avg_er_pct != 5.4';
  ASSERT (SELECT trend_direction    FROM analytics.v_account_summaries WHERE handle='dummy_b') = 'flat', 'b trend != flat';
  ASSERT (SELECT trend_change_pct   FROM analytics.v_account_summaries WHERE handle='dummy_b') = 0,      'b trend_pct != 0';
  ASSERT (SELECT trend_older_avg    FROM analytics.v_account_summaries WHERE handle='dummy_b') IS NULL,  'b older not null';
  ASSERT (SELECT trend_newer_avg    FROM analytics.v_account_summaries WHERE handle='dummy_b') = 1000,   'b newer != 1000';
  ASSERT (SELECT sponsored_count    FROM analytics.v_account_summaries WHERE handle='dummy_b') = 0,      'b sponsored != 0';
  ASSERT (SELECT organic_avg        FROM analytics.v_account_summaries WHERE handle='dummy_b') = 1000,   'b organic_avg != 1000';
  ASSERT (SELECT ad_avg             FROM analytics.v_account_summaries WHERE handle='dummy_b') IS NULL,  'b ad_avg not null';
  ASSERT (SELECT ad_drop_pct        FROM analytics.v_account_summaries WHERE handle='dummy_b') IS NULL,  'b drop not null';
  ASSERT (SELECT last_ad_posted_at  FROM analytics.v_account_summaries WHERE handle='dummy_b') IS NULL,  'b last_ad not null';
  ASSERT (SELECT avg_interval_days  FROM analytics.v_account_summaries WHERE handle='dummy_b') IS NULL,  'b interval not null (n=1)';
END $$;

-- dummy_v: metric 'views' + 트렌드 down + 광고 양쪽 비교 + 프로필 확장
DO $$
BEGIN
  ASSERT (SELECT follows_count      FROM analytics.v_account_summaries WHERE handle='dummy_v') = 300,    'v follows_count != 300';
  ASSERT (SELECT posts_count        FROM analytics.v_account_summaries WHERE handle='dummy_v') = 80,     'v posts_count != 80';
  ASSERT (SELECT biography          FROM analytics.v_account_summaries WHERE handle='dummy_v') = '글로우 크리에이터', 'v biography wrong';
  ASSERT (SELECT analyzed_count     FROM analytics.v_account_summaries WHERE handle='dummy_v') = 6,      'v analyzed != 6';
  ASSERT (SELECT metric             FROM analytics.v_account_summaries WHERE handle='dummy_v') = 'views', 'v metric != views';
  ASSERT (SELECT avg_views          FROM analytics.v_account_summaries WHERE handle='dummy_v') = 14000,  'v avg_views != 14000';
  ASSERT (SELECT views_per_follower FROM analytics.v_account_summaries WHERE handle='dummy_v') = 1.4,    'v vpf != 1.4';
  ASSERT (SELECT avg_er_pct         FROM analytics.v_account_summaries WHERE handle='dummy_v') = 3.0,    'v avg_er_pct != 3.0';
  ASSERT (SELECT avg_likes          FROM analytics.v_account_summaries WHERE handle='dummy_v') = 275,    'v avg_likes != 275';
  ASSERT (SELECT avg_comments       FROM analytics.v_account_summaries WHERE handle='dummy_v') = 28,     'v avg_comments != 28';
  ASSERT (SELECT trend_direction    FROM analytics.v_account_summaries WHERE handle='dummy_v') = 'down', 'v trend != down';
  ASSERT (SELECT trend_change_pct   FROM analytics.v_account_summaries WHERE handle='dummy_v') = -60,    'v trend_pct != -60';
  ASSERT (SELECT trend_older_avg    FROM analytics.v_account_summaries WHERE handle='dummy_v') = 20000,  'v older != 20000';
  ASSERT (SELECT trend_newer_avg    FROM analytics.v_account_summaries WHERE handle='dummy_v') = 8000,   'v newer != 8000';
  ASSERT (SELECT sponsored_count    FROM analytics.v_account_summaries WHERE handle='dummy_v') = 2,      'v sponsored != 2';
  ASSERT (SELECT organic_avg        FROM analytics.v_account_summaries WHERE handle='dummy_v') = 13500,  'v organic_avg != 13500';
  ASSERT (SELECT ad_avg             FROM analytics.v_account_summaries WHERE handle='dummy_v') = 15000,  'v ad_avg != 15000';
  ASSERT (SELECT ad_drop_pct        FROM analytics.v_account_summaries WHERE handle='dummy_v') = -11,    'v drop != -11';
  ASSERT (SELECT comparison_organic_count FROM analytics.v_account_summaries WHERE handle='dummy_v') = 4, 'v cmp_og != 4';
  ASSERT (SELECT comparison_ad_count      FROM analytics.v_account_summaries WHERE handle='dummy_v') = 2, 'v cmp_ad != 2';
  ASSERT (SELECT last_ad_posted_at  FROM analytics.v_account_summaries WHERE handle='dummy_v') = timestamptz '2026-05-29 09:00:00+09', 'v last_ad wrong';
  ASSERT (SELECT avg_interval_days  FROM analytics.v_account_summaries WHERE handle='dummy_v') = 7.0,    'v interval != 7.0';
END $$;

-- dummy_flat: ±15% 이내 → flat이지만 change_pct는 원값(-2) 유지
DO $$
BEGIN
  ASSERT (SELECT metric           FROM analytics.v_account_summaries WHERE handle='dummy_flat') = 'views', 'flat metric != views';
  ASSERT (SELECT avg_views        FROM analytics.v_account_summaries WHERE handle='dummy_flat') = 10375,   'flat avg_views != 10375';
  ASSERT (SELECT views_per_follower FROM analytics.v_account_summaries WHERE handle='dummy_flat') = 1.3,   'flat vpf != 1.3';
  ASSERT (SELECT avg_er_pct       FROM analytics.v_account_summaries WHERE handle='dummy_flat') = 2.8,     'flat avg_er_pct != 2.8';
  ASSERT (SELECT avg_likes        FROM analytics.v_account_summaries WHERE handle='dummy_flat') = 201,     'flat avg_likes != 201';
  ASSERT (SELECT avg_comments     FROM analytics.v_account_summaries WHERE handle='dummy_flat') = 21,      'flat avg_comments != 21';
  ASSERT (SELECT trend_direction  FROM analytics.v_account_summaries WHERE handle='dummy_flat') = 'flat',  'flat trend != flat';
  ASSERT (SELECT trend_change_pct FROM analytics.v_account_summaries WHERE handle='dummy_flat') = -2,      'flat trend_pct != -2';
  ASSERT (SELECT sponsored_count  FROM analytics.v_account_summaries WHERE handle='dummy_flat') = 0,       'flat sponsored != 0';
  ASSERT (SELECT organic_avg      FROM analytics.v_account_summaries WHERE handle='dummy_flat') = 10375,   'flat organic_avg != 10375';
  ASSERT (SELECT ad_avg           FROM analytics.v_account_summaries WHERE handle='dummy_flat') IS NULL,   'flat ad_avg not null';
  ASSERT (SELECT avg_interval_days FROM analytics.v_account_summaries WHERE handle='dummy_flat') = 7.0,    'flat interval != 7.0';
END $$;

-- ===== v_account_category_stats =====
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM analytics.v_account_category_stats WHERE account_handle LIKE 'dummy_%') = 5,
    'category rows != 5';
  ASSERT (SELECT content_count FROM analytics.v_account_category_stats WHERE account_handle='dummy_a' AND main_group='A') = 2,
    'a/A count != 2';
  ASSERT (SELECT content_count FROM analytics.v_account_category_stats WHERE account_handle='dummy_a' AND main_group='B') = 1,
    'a/B count != 1';
  ASSERT (SELECT content_count FROM analytics.v_account_category_stats WHERE account_handle='dummy_v' AND main_group='B') = 6,
    'v/B count != 6';
END $$;

-- ===== v_account_content_series =====
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM analytics.v_account_content_series WHERE account_handle LIKE 'dummy_%') = 14,
    'series rows != 14';
  -- 피드 views NULL 보존 + 광고 플래그 + content_type 소문자
  ASSERT (SELECT views     FROM analytics.v_account_content_series WHERE short_code='dummy_f1') IS NULL, 'f1 views not null';
  ASSERT (SELECT sponsored FROM analytics.v_account_content_series WHERE short_code='dummy_f1') = true,  'f1 sponsored != true';
  ASSERT (SELECT content_type FROM analytics.v_account_content_series WHERE short_code='dummy_f1') = 'feed', 'f1 type != feed';
  ASSERT (SELECT views     FROM analytics.v_account_content_series WHERE short_code='dummy_r2') = 7000,  'r2 views != 7000';
  ASSERT (SELECT likes     FROM analytics.v_account_content_series WHERE short_code='dummy_r2') = 300,   'r2 likes != 300';
  ASSERT (SELECT comments  FROM analytics.v_account_content_series WHERE short_code='dummy_r2') = 30,    'r2 comments != 30';
  ASSERT (SELECT content_type FROM analytics.v_account_content_series WHERE short_code='dummy_r2') = 'reels', 'r2 type != reels';
  ASSERT (SELECT posted_at FROM analytics.v_account_content_series WHERE short_code='dummy_r2') = timestamptz '2026-06-02 09:00:00+09', 'r2 posted_at wrong';
END $$;
