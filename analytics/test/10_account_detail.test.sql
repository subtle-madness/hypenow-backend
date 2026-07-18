-- 그룹 10 기대값. 산식 정본: celfit-front parse_accounts_recent.py (스펙 §3).
-- 신스키마 주의: content의 광고·카테고리 컬럼이 소멸해 base 뷰가 ad_marked=false·main_group=NULL
-- 상수로 계약만 유지한다(캡션 LLM 분류로 이관 예정) — 광고 비교 블록은 전 계정 비활성
-- (sponsored=0·ad_avg NULL·organic=전체 평균), 카테고리 믹스는 빈 결과가 "정상"이다.
-- 결정성: 이 그룹이 읽는 설정 키를 기본값으로 강제.
DELETE FROM app_setting WHERE key IN ('analytics.recent-window', 'analytics.trend-threshold');

-- ===== 추가 픽스처: metric 'views'·트렌드 down/flat·프로필 확장 검증용 (인플루언서 9909005~9909006) =====
-- dummy_v는 SELF_GQL 형태로 edge_* 카운트 경로를 검증 (HIKER_MOBILE 전체 키는 00 테스트가 커버).
INSERT INTO influencer(id, username) VALUES (9909005,'dummy_v'), (9909006,'dummy_flat');
INSERT INTO raw_profile(influencer_id, crawl_run_id, source, username, followers, payload, captured_at) VALUES
 (9909005,9909990,'SELF_GQL','dummy_v',10000,
  '{"data":{"user":{"username":"dummy_v","edge_followed_by":{"count":10000},"edge_follow":{"count":300},"edge_owner_to_timeline_media":{"count":80},"biography":"글로우 크리에이터"}}}'::jsonb,
  timestamptz '2026-06-06 00:00:00+09'),
 (9909006,9909990,'DATALIKERS','dummy_flat',8000,
  '{"username":"dummy_flat","follower_count":8000}'::jsonb,
  timestamptz '2026-06-06 00:00:00+09');

INSERT INTO content(id, short_code, content_type, owner_username, influencer_id, uploaded_at, status, origin, first_seen_at) VALUES
 (9909110,'dummy_v1','REELS','dummy_v',9909005,    timestamptz '2026-05-01 09:00:00+09','COLLECTED','ENUMERATION', timestamptz '2026-05-01 00:00:00+09'),
 (9909111,'dummy_v2','REELS','dummy_v',9909005,    timestamptz '2026-05-08 09:00:00+09','COLLECTED','ENUMERATION', timestamptz '2026-05-08 00:00:00+09'),
 (9909112,'dummy_v3','REELS','dummy_v',9909005,    timestamptz '2026-05-15 09:00:00+09','COLLECTED','ENUMERATION', timestamptz '2026-05-15 00:00:00+09'),
 (9909113,'dummy_v4','REELS','dummy_v',9909005,    timestamptz '2026-05-22 09:00:00+09','COLLECTED','ENUMERATION', timestamptz '2026-05-22 00:00:00+09'),
 (9909114,'dummy_v5','REELS','dummy_v',9909005,    timestamptz '2026-05-29 09:00:00+09','COLLECTED','ENUMERATION', timestamptz '2026-05-29 00:00:00+09'),
 (9909115,'dummy_v6','REELS','dummy_v',9909005,    timestamptz '2026-06-05 09:00:00+09','COLLECTED','ENUMERATION', timestamptz '2026-06-05 00:00:00+09'),
 (9909120,'dummy_t1','REELS','dummy_flat',9909006, timestamptz '2026-05-01 09:00:00+09','COLLECTED','ENUMERATION', timestamptz '2026-05-01 00:00:00+09'),
 (9909121,'dummy_t2','REELS','dummy_flat',9909006, timestamptz '2026-05-08 09:00:00+09','COLLECTED','ENUMERATION', timestamptz '2026-05-08 00:00:00+09'),
 (9909122,'dummy_t3','REELS','dummy_flat',9909006, timestamptz '2026-05-15 09:00:00+09','COLLECTED','ENUMERATION', timestamptz '2026-05-15 00:00:00+09'),
 (9909123,'dummy_t4','REELS','dummy_flat',9909006, timestamptz '2026-05-22 09:00:00+09','COLLECTED','ENUMERATION', timestamptz '2026-05-22 00:00:00+09');

-- 열거 페이지: 계정당 1장에 전체 아이템 (계정 집계는 최신 상세만 쓰므로 페이지 누적 불필요)
INSERT INTO raw_media_page(id, influencer_id, crawl_run_id, source, payload, captured_at) VALUES
 (9909421,9909005,9909990,'HIKER_V2_CLIPS',
  '{"response":{"items":[
     {"media":{"code":"dummy_v1","media_type":2,"like_count":400,"comment_count":40,"play_count":20000}},
     {"media":{"code":"dummy_v2","media_type":2,"like_count":300,"comment_count":30,"play_count":18000}},
     {"media":{"code":"dummy_v3","media_type":2,"like_count":500,"comment_count":50,"play_count":22000}},
     {"media":{"code":"dummy_v4","media_type":2,"like_count":200,"comment_count":20,"play_count":10000}},
     {"media":{"code":"dummy_v5","media_type":2,"like_count":150,"comment_count":15,"play_count":8000}},
     {"media":{"code":"dummy_v6","media_type":2,"like_count":100,"comment_count":10,"play_count":6000}}
   ]}}'::jsonb, timestamptz '2026-06-06 09:00:00+09'),
 (9909422,9909006,9909990,'HIKER_V2_CLIPS',
  '{"response":{"items":[
     {"media":{"code":"dummy_t1","media_type":2,"like_count":200,"comment_count":20,"play_count":10000}},
     {"media":{"code":"dummy_t2","media_type":2,"like_count":210,"comment_count":21,"play_count":11000}},
     {"media":{"code":"dummy_t3","media_type":2,"like_count":190,"comment_count":19,"play_count":9000}},
     {"media":{"code":"dummy_t4","media_type":2,"like_count":205,"comment_count":25,"play_count":11500}}
   ]}}'::jsonb, timestamptz '2026-06-06 09:00:00+09');

-- ===== v_account_summaries =====
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM analytics.v_account_summaries WHERE handle LIKE 'dummy_%') = 4,
    'summaries rows != 4';
END $$;

-- dummy_a: metric 'likes' 폴백 + 피드 NULL 함정 + 트렌드 up. 광고는 ad_marked=false 고정으로 비활성.
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
  -- 광고 중립화: sponsored 0, organic = 전체 평균(940), ad 쪽 전부 NULL
  ASSERT (SELECT sponsored_count    FROM analytics.v_account_summaries WHERE handle='dummy_a') = 0,     'a sponsored != 0 (ad_marked=false 고정)';
  ASSERT (SELECT organic_avg        FROM analytics.v_account_summaries WHERE handle='dummy_a') = 940,   'a organic_avg != 940 (전체 평균)';
  ASSERT (SELECT ad_avg             FROM analytics.v_account_summaries WHERE handle='dummy_a') IS NULL, 'a ad_avg not null';
  ASSERT (SELECT ad_drop_pct        FROM analytics.v_account_summaries WHERE handle='dummy_a') IS NULL, 'a drop not null';
  ASSERT (SELECT comparison_organic_count FROM analytics.v_account_summaries WHERE handle='dummy_a') = 3, 'a cmp_og != 3';
  ASSERT (SELECT comparison_ad_count      FROM analytics.v_account_summaries WHERE handle='dummy_a') = 0, 'a cmp_ad != 0';
  ASSERT (SELECT last_ad_posted_at  FROM analytics.v_account_summaries WHERE handle='dummy_a') IS NULL, 'a last_ad not null';
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

-- dummy_v: metric 'views' + 트렌드 down + 프로필 확장(SELF_GQL edge_* 카운트 경로)
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
  -- 광고 중립화: 구픽스처의 v3·v5 ad_marked=true 시나리오는 ad_marked 소멸로 재현 불가
  ASSERT (SELECT sponsored_count    FROM analytics.v_account_summaries WHERE handle='dummy_v') = 0,      'v sponsored != 0 (ad_marked=false 고정)';
  ASSERT (SELECT organic_avg        FROM analytics.v_account_summaries WHERE handle='dummy_v') = 14000,  'v organic_avg != 14000 (전체 평균)';
  ASSERT (SELECT ad_avg             FROM analytics.v_account_summaries WHERE handle='dummy_v') IS NULL,  'v ad_avg not null';
  ASSERT (SELECT ad_drop_pct        FROM analytics.v_account_summaries WHERE handle='dummy_v') IS NULL,  'v drop not null';
  ASSERT (SELECT comparison_organic_count FROM analytics.v_account_summaries WHERE handle='dummy_v') = 6, 'v cmp_og != 6';
  ASSERT (SELECT comparison_ad_count      FROM analytics.v_account_summaries WHERE handle='dummy_v') = 0, 'v cmp_ad != 0';
  ASSERT (SELECT last_ad_posted_at  FROM analytics.v_account_summaries WHERE handle='dummy_v') IS NULL,  'v last_ad not null';
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
-- main_group이 base 뷰 NULL 상수라 빈 결과가 계약이다 (캡션 LLM 분류 개통 시 이 기대값을 되살린다)
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM analytics.v_account_category_stats WHERE account_handle LIKE 'dummy_%') = 0,
    'category rows != 0 (main_group NULL 고정 — 어휘 이관 전까지 빈 결과)';
END $$;

-- ===== v_account_content_series =====
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM analytics.v_account_content_series WHERE account_handle LIKE 'dummy_%') = 14,
    'series rows != 14';
  -- 피드 views NULL 보존 + 광고 플래그(중립화 false) + content_type 소문자
  ASSERT (SELECT views     FROM analytics.v_account_content_series WHERE short_code='dummy_f1') IS NULL, 'f1 views not null';
  ASSERT (SELECT sponsored FROM analytics.v_account_content_series WHERE short_code='dummy_f1') = false, 'f1 sponsored != false (ad_marked 고정)';
  ASSERT (SELECT content_type FROM analytics.v_account_content_series WHERE short_code='dummy_f1') = 'feed', 'f1 type != feed';
  ASSERT (SELECT views     FROM analytics.v_account_content_series WHERE short_code='dummy_r2') = 7000,  'r2 views != 7000';
  ASSERT (SELECT likes     FROM analytics.v_account_content_series WHERE short_code='dummy_r2') = 300,   'r2 likes != 300';
  ASSERT (SELECT comments  FROM analytics.v_account_content_series WHERE short_code='dummy_r2') = 30,    'r2 comments != 30';
  ASSERT (SELECT content_type FROM analytics.v_account_content_series WHERE short_code='dummy_r2') = 'reels', 'r2 type != reels';
  ASSERT (SELECT posted_at FROM analytics.v_account_content_series WHERE short_code='dummy_r2') = timestamptz '2026-06-02 09:00:00+09', 'r2 posted_at wrong';
END $$;
