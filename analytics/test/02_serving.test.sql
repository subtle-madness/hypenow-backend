-- 서빙 뷰 기대값. 시드 근거:
--   v_accounts 2행 (dummy_a followers=5500 최신)
--   v_contents: 상세 있는 콘텐츠만 = 5행 (시드 4건 + 아래 폴백 픽스처 dummy_rf)
--     지표는 업로드 +3일 이후 가장 이른 스냅샷 고정 (07-14 정정 ③):
--       dummy_r1: 스냅샷 06-04(=+3일 정각, 경계 포함)/06-05/06-06 → 고정=06-04 → views 10000
--       dummy_r2=7000, dummy_r3=40000, dummy_f1=2000+100=2100 (각 +3일 정각 1건)
--       dummy_rf: 스냅샷 전부 +3일 미만 → 최신 폴백 → views 1500
--     메타는 최신 스냅샷: dummy_r1 caption='cap r1'(고정 스냅샷은 'cap r1 old'), 썸네일=dummy_r1_new.jpg
--     hype_score: 릴스=views, 피드=likes+comments
--   content_type 소문자 매핑: REELS→reels, FEED→feed
--   v_content_comments 3행, author_masked = left(writer,3)||'***' → dummy_fan1→dum***
--   v_content_metric_snapshots: 스냅샷 전체 = 8행 (9101 3행 + 9105 2행 + 나머지 3행) — 고정과 무관하게 전량 이력
--   app_setting 'analytics.metric-pin-days' 오버라이드(3→4) 시 dummy_r1 고정=06-05 → views 11000

-- 폴백 픽스처: 업로드 +3일 안에만 수집된 콘텐츠 (구크롤러 조기 수집 잔재 재현)
INSERT INTO content(id, short_code, content_type, owner_username, uploaded_at, category_id, discovery_keyword, status, first_seen_at, subcategory, main_group, ad_marked) VALUES
 (9105,'dummy_rf','REELS','dummy_a', timestamptz '2026-06-05 09:00:00+09',999,'makeup','AGGREGATED', timestamptz '2026-06-05 00:00:00+09','makeup_sub','A', false);
INSERT INTO raw_post_detail(content_id, crawl_run_id, payload, captured_at) VALUES
 (9105,9990,'{"shortCode":"dummy_rf","type":"Video","caption":"cap rf","likesCount":100,"commentsCount":10,"videoPlayCount":1000,"videoDuration":10,"displayUrl":"https://thumb/dummy_rf.jpg","url":"https://www.instagram.com/p/dummy_rf/"}'::jsonb, timestamptz '2026-06-06 09:00:00+09'),
 (9105,9990,'{"shortCode":"dummy_rf","type":"Video","caption":"cap rf","likesCount":150,"commentsCount":15,"videoPlayCount":1500,"videoDuration":10,"displayUrl":"https://thumb/dummy_rf.jpg","url":"https://www.instagram.com/p/dummy_rf/"}'::jsonb, timestamptz '2026-06-07 09:00:00+09');

DO $$
BEGIN
  ASSERT (SELECT count(*) FROM analytics.v_accounts WHERE handle LIKE 'dummy_%') = 2,
    'v_accounts dummy rows != 2';
  ASSERT (SELECT followers FROM analytics.v_accounts WHERE handle = 'dummy_a') = 5500,
    'v_accounts dummy_a followers != 5500';
  ASSERT (SELECT display_name FROM analytics.v_accounts WHERE handle = 'dummy_a') = '더미 에이',
    'v_accounts dummy_a display_name mismatch';

  ASSERT (SELECT count(*) FROM analytics.v_contents WHERE short_code LIKE 'dummy_%') = 5,
    'v_contents dummy rows != 5';

  -- 지표 고정: +3일 정각 스냅샷(경계 포함)이 가장 이른 성숙분 — 이후 스냅샷(11000)을 따라가면 안 된다
  ASSERT (SELECT hype_score FROM analytics.v_contents WHERE short_code = 'dummy_r1') = 10000,
    'v_contents dummy_r1 hype_score != pinned views 10000 (+3일 고정, 최신 11000 아님)';
  ASSERT (SELECT likes FROM analytics.v_contents WHERE short_code = 'dummy_r1') = 500,
    'v_contents dummy_r1 likes != pinned 500';
  -- 메타는 최신 스냅샷 유지 (고정 스냅샷 caption은 cap r1 old / 썸네일은 dummy_r1_old.jpg)
  ASSERT (SELECT caption FROM analytics.v_contents WHERE short_code = 'dummy_r1') = 'cap r1',
    'v_contents dummy_r1 caption != latest cap r1 (메타는 최신)';
  ASSERT (SELECT thumbnail_url FROM analytics.v_contents WHERE short_code = 'dummy_r1') = 'https://thumb/dummy_r1_new.jpg',
    'v_contents dummy_r1 thumbnail != latest dummy_r1_new.jpg (메타는 최신)';

  ASSERT (SELECT hype_score FROM analytics.v_contents WHERE short_code = 'dummy_f1') = 2100,
    'v_contents dummy_f1 hype_score != likes+comments 2100';
  ASSERT (SELECT content_type FROM analytics.v_contents WHERE short_code = 'dummy_f1') = 'feed',
    'v_contents dummy_f1 content_type != feed (lowercase)';
  ASSERT (SELECT account_handle FROM analytics.v_contents WHERE short_code = 'dummy_r3') = 'dummy_b',
    'v_contents dummy_r3 account_handle != dummy_b';

  -- 최신 폴백: 성숙 스냅샷이 없으면 최신 스냅샷으로 서빙 (제외하지 않는다)
  ASSERT (SELECT hype_score FROM analytics.v_contents WHERE short_code = 'dummy_rf') = 1500,
    'v_contents dummy_rf hype_score != latest fallback 1500';

  ASSERT (SELECT count(*) FROM analytics.v_content_comments WHERE short_code = 'dummy_r1') = 3,
    'v_content_comments dummy_r1 rows != 3';
  ASSERT (SELECT count(*) FROM analytics.v_content_comments
          WHERE short_code = 'dummy_r1' AND author_masked = 'dum***') = 3,
    'v_content_comments masking != dum*** (left 3 + ***)';
  ASSERT (SELECT max(like_count) FROM analytics.v_content_comments WHERE short_code = 'dummy_r1') = 7,
    'v_content_comments max like_count != 7';

  -- 이력은 고정과 무관하게 전량 보존 (as-of·추이 참조용)
  ASSERT (SELECT count(*) FROM analytics.v_content_metric_snapshots WHERE short_code LIKE 'dummy_%') = 8,
    'v_content_metric_snapshots dummy rows != 8';
  ASSERT (SELECT count(*) FROM analytics.v_content_metric_snapshots WHERE short_code = 'dummy_r1') = 3,
    'v_content_metric_snapshots dummy_r1 snapshots != 3 (구/중/신)';
  ASSERT (SELECT hype_score FROM analytics.v_content_metric_snapshots
          WHERE short_code = 'dummy_r1' ORDER BY captured_at ASC LIMIT 1) = 10000,
    'v_content_metric_snapshots dummy_r1 old hype_score != views 10000';
  ASSERT (SELECT hype_score FROM analytics.v_content_metric_snapshots
          WHERE short_code = 'dummy_r1' ORDER BY captured_at DESC LIMIT 1) = 11000,
    'v_content_metric_snapshots dummy_r1 new hype_score != views 11000';
  ASSERT (SELECT hype_score FROM analytics.v_content_metric_snapshots WHERE short_code = 'dummy_f1') = 2100,
    'v_content_metric_snapshots dummy_f1 hype_score != likes+comments 2100';

  -- 고정 경과일은 app_setting으로 런타임 조정 (3→4일이면 dummy_r1 고정이 06-05 스냅샷으로 이동)
  INSERT INTO app_setting(key, value) VALUES ('analytics.metric-pin-days', '4')
  ON CONFLICT (key) DO UPDATE SET value = '4';
  ASSERT (SELECT hype_score FROM analytics.v_contents WHERE short_code = 'dummy_r1') = 11000,
    'v_contents dummy_r1 pin-days=4 hype_score != 11000 (설정 오버라이드)';
END $$;
