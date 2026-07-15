-- 서빙 뷰 기대값. 시드 근거:
--   v_accounts 2행 (dummy_a followers=5500 최신, external_link=https://link.example/a)
--   v_contents: 상세 있는 콘텐츠만 = 8행 (시드 4건 + 폴백 픽스처 dummy_rf + hype 픽스처 3건)
--     지표는 업로드 +3일 이후 가장 이른 스냅샷 고정 (07-14 정정 ③):
--       dummy_r1: 스냅샷 06-04(=+3일 정각, 경계 포함)/06-05/06-06 → 고정=06-04 → views 10000
--       dummy_r2 views=7000, dummy_r3 views=40000, dummy_f1 likes=2000·comments=100 (각 +3일 정각 1건)
--       dummy_rf: 스냅샷 전부 +3일 미만 → 최신 폴백 → views 1500
--     메타는 최신 스냅샷: dummy_r1 caption='cap r1'(고정 스냅샷은 'cap r1 old'), 썸네일=dummy_r1_new.jpg
--     hype_score: 스펙 5.4 산식 0~100 (구 원값 방식 폐기) —
--       릴스 = round(cbrt(reach×engage×fresh)×100), 피드 = round(cbrt(팔로워ER축²×fresh)×100)
--       reach=LEAST(ln(1+views/(followers+1000))/ln(31),1) · engage=LEAST(LEAST((likes+comments×3)/views,0.5)/0.12,1)
--       fresh: v_contents=now() 기준, v_content_metric_snapshots=captured_at 기준
--       릴스인데 views NULL → NULL. 산식 등식 검증은 상대 시각 hype 픽스처(dummy_hype_*)로만 한다
--       (시드 4건은 고정 달력 시각이라 now() 기준 fresh가 비결정적 — views/likes 등식으로 고정만 검증).
--   content_type 소문자 매핑: REELS→reels, FEED→feed
--   v_content_comments 3행, author_masked = left(writer,3)||'***' → dummy_fan1→dum***
--   v_content_metric_snapshots: 스냅샷 전체 = 11행 (9101 3행 + 9105 2행 + 나머지 3행 + hype 픽스처 3행) — 고정과 무관하게 전량 이력
--   app_setting 'analytics.metric-pin-days' 오버라이드(3→4) 시 dummy_r1 고정=06-05 → views 11000

-- 폴백 픽스처: 업로드 +3일 안에만 수집된 콘텐츠 (구크롤러 조기 수집 잔재 재현)
INSERT INTO content(id, short_code, content_type, owner_username, uploaded_at, category_id, discovery_keyword, status, first_seen_at, subcategory, main_group, ad_marked) VALUES
 (9105,'dummy_rf','REELS','dummy_a', timestamptz '2026-06-05 09:00:00+09',999,'makeup','AGGREGATED', timestamptz '2026-06-05 00:00:00+09','makeup_sub','A', false);
INSERT INTO raw_post_detail(content_id, crawl_run_id, payload, captured_at) VALUES
 (9105,9990,'{"shortCode":"dummy_rf","type":"Video","caption":"cap rf","likesCount":100,"commentsCount":10,"videoPlayCount":1000,"videoDuration":10,"displayUrl":"https://thumb/dummy_rf.jpg","url":"https://www.instagram.com/p/dummy_rf/"}'::jsonb, timestamptz '2026-06-06 09:00:00+09'),
 (9105,9990,'{"shortCode":"dummy_rf","type":"Video","caption":"cap rf","likesCount":150,"commentsCount":15,"videoPlayCount":1500,"videoDuration":10,"displayUrl":"https://thumb/dummy_rf.jpg","url":"https://www.instagram.com/p/dummy_rf/"}'::jsonb, timestamptz '2026-06-07 09:00:00+09');

-- hype 산식 픽스처: 상대 시각 — BEGIN/ROLLBACK 트랜잭션 안에서는 now()가 고정이라 결정적.
-- 게시 7일 전(v_contents fresh 정확히 0.5), 스냅샷 +3일(성숙 경계 포함, 스냅샷 뷰 fresh=0.5^(3/7)).
-- ① 릴스 dummy_hype_r: views=165000, likes=3300, comments=100, followers=5500 (dummy_a)
--    reach = ln(1+165000/6500)/ln(31) ≈ 0.9531 · engage = min(min(3600/165000,0.5)/0.12,1) ≈ 0.1818
--    fresh = 0.5 → score = round(cbrt(0.9531×0.1818×0.5)×100) = round(44.25) = 44
-- ② 피드 dummy_hype_f: likes=550, comments=50 → er=(550+150)/6500≈0.1077, min(0.1077,0.3)/0.10=1.077→축 캡 1.0
--    v_contents: round(cbrt(1²×0.5)×100) = round(79.37) = 79
--    스냅샷 뷰: round(cbrt(1²×0.5^(3/7))×100) = round(0.5^(1/7)×100) = round(90.57) = 91
-- ③ 릴스 views NULL dummy_hype_rn: 조회수 필드 없음 → hype_score NULL (NULLS LAST 정렬 규칙)
INSERT INTO content(id, short_code, content_type, owner_username, uploaded_at, category_id, discovery_keyword, status, first_seen_at, subcategory, main_group, ad_marked) VALUES
 (9201,'dummy_hype_r','REELS','dummy_a', now() - interval '7 days', 999,'makeup','AGGREGATED', now() - interval '7 days','makeup_sub','A', false),
 (9202,'dummy_hype_f','FEED', 'dummy_a', now() - interval '7 days', 999,'glow','AGGREGATED',   now() - interval '7 days','glow_sub','B', false),
 (9203,'dummy_hype_rn','REELS','dummy_a', now() - interval '7 days', 999,'makeup','AGGREGATED', now() - interval '7 days','makeup_sub','A', false);
INSERT INTO raw_post_detail(content_id, crawl_run_id, payload, captured_at) VALUES
 (9201,9990,'{"shortCode":"dummy_hype_r","type":"Video","caption":"cap hype r","likesCount":3300,"commentsCount":100,"videoPlayCount":165000,"videoDuration":10,"displayUrl":"https://thumb/hype_r.jpg","url":"https://www.instagram.com/p/dummy_hype_r/"}'::jsonb, now() - interval '4 days'),
 (9202,9990,'{"shortCode":"dummy_hype_f","type":"Image","caption":"cap hype f","likesCount":550,"commentsCount":50,"displayUrl":"https://thumb/hype_f.jpg","url":"https://www.instagram.com/p/dummy_hype_f/"}'::jsonb, now() - interval '4 days'),
 (9203,9990,'{"shortCode":"dummy_hype_rn","type":"Video","caption":"cap hype rn","likesCount":100,"commentsCount":10,"videoDuration":10,"displayUrl":"https://thumb/hype_rn.jpg","url":"https://www.instagram.com/p/dummy_hype_rn/"}'::jsonb, now() - interval '4 days');

DO $$
BEGIN
  ASSERT (SELECT count(*) FROM analytics.v_accounts WHERE handle LIKE 'dummy_%') = 2,
    'v_accounts dummy rows != 2';
  ASSERT (SELECT followers FROM analytics.v_accounts WHERE handle = 'dummy_a') = 5500,
    'v_accounts dummy_a followers != 5500';
  ASSERT (SELECT display_name FROM analytics.v_accounts WHERE handle = 'dummy_a') = '더미 에이',
    'v_accounts dummy_a display_name mismatch';
  ASSERT (SELECT external_link FROM analytics.v_accounts WHERE handle = 'dummy_a') = 'https://link.example/a',
    'v_accounts dummy_a external_link mismatch';
  ASSERT (SELECT external_link FROM analytics.v_accounts WHERE handle = 'dummy_b') IS NULL,
    'v_accounts dummy_b external_link not null (payload has no key)';

  ASSERT (SELECT count(*) FROM analytics.v_contents WHERE short_code LIKE 'dummy_%') = 8,
    'v_contents dummy rows != 8';

  -- 지표 고정: +3일 정각 스냅샷(경계 포함)이 가장 이른 성숙분 — 이후 스냅샷(11000)을 따라가면 안 된다
  ASSERT (SELECT views FROM analytics.v_contents WHERE short_code = 'dummy_r1') = 10000,
    'v_contents dummy_r1 views != pinned 10000 (+3일 고정, 최신 11000 아님)';
  ASSERT (SELECT likes FROM analytics.v_contents WHERE short_code = 'dummy_r1') = 500,
    'v_contents dummy_r1 likes != pinned 500';
  ASSERT (SELECT metric_captured_at FROM analytics.v_contents WHERE short_code = 'dummy_r1')
         = timestamptz '2026-06-04 09:00:00+09',
    'v_contents dummy_r1 metric_captured_at != 고정 스냅샷 06-04';
  -- 메타는 최신 스냅샷 유지 (고정 스냅샷 caption은 cap r1 old / 썸네일은 dummy_r1_old.jpg)
  ASSERT (SELECT caption FROM analytics.v_contents WHERE short_code = 'dummy_r1') = 'cap r1',
    'v_contents dummy_r1 caption != latest cap r1 (메타는 최신)';
  ASSERT (SELECT thumbnail_url FROM analytics.v_contents WHERE short_code = 'dummy_r1') = 'https://thumb/dummy_r1_new.jpg',
    'v_contents dummy_r1 thumbnail != latest dummy_r1_new.jpg (메타는 최신)';

  ASSERT (SELECT likes FROM analytics.v_contents WHERE short_code = 'dummy_f1') = 2000
     AND (SELECT comments FROM analytics.v_contents WHERE short_code = 'dummy_f1') = 100,
    'v_contents dummy_f1 pinned likes/comments != 2000/100';
  ASSERT (SELECT content_type FROM analytics.v_contents WHERE short_code = 'dummy_f1') = 'feed',
    'v_contents dummy_f1 content_type != feed (lowercase)';
  ASSERT (SELECT account_handle FROM analytics.v_contents WHERE short_code = 'dummy_r3') = 'dummy_b',
    'v_contents dummy_r3 account_handle != dummy_b';

  -- 최신 폴백: 성숙 스냅샷이 없으면 최신 스냅샷으로 서빙 (제외하지 않는다)
  ASSERT (SELECT views FROM analytics.v_contents WHERE short_code = 'dummy_rf') = 1500,
    'v_contents dummy_rf views != latest fallback 1500';

  -- hype 산식 (스펙 5.4): 픽스처 주석의 산수 참조
  ASSERT (SELECT hype_score FROM analytics.v_contents WHERE short_code = 'dummy_hype_r') = 44,
    'hype 릴스 산식: 기대 44 != ' ||
    (SELECT hype_score FROM analytics.v_contents WHERE short_code = 'dummy_hype_r');
  ASSERT (SELECT hype_score BETWEEN 0 AND 100 FROM analytics.v_contents WHERE short_code = 'dummy_hype_r'),
    'hype 범위 0~100 위반';
  ASSERT (SELECT pg_typeof(hype_score)::text FROM analytics.v_contents WHERE short_code = 'dummy_hype_r') = 'bigint',
    'hype_score 타입 != bigint (record Long 매핑)';
  ASSERT (SELECT hype_score FROM analytics.v_contents WHERE short_code = 'dummy_hype_f') = 79,
    'hype 피드 산식: 기대 79 != ' ||
    (SELECT hype_score FROM analytics.v_contents WHERE short_code = 'dummy_hype_f');
  ASSERT (SELECT hype_score FROM analytics.v_contents WHERE short_code = 'dummy_hype_rn') IS NULL,
    'hype 릴스 views NULL: hype_score가 NULL이 아님';
  ASSERT (SELECT metric_captured_at FROM analytics.v_contents WHERE short_code = 'dummy_hype_r')
         = (SELECT captured_at FROM analytics.v_content_metric_snapshots WHERE short_code = 'dummy_hype_r'),
    'metric_captured_at != 고정 스냅샷 captured_at';

  ASSERT (SELECT count(*) FROM analytics.v_content_comments WHERE short_code = 'dummy_r1') = 3,
    'v_content_comments dummy_r1 rows != 3';
  ASSERT (SELECT count(*) FROM analytics.v_content_comments
          WHERE short_code = 'dummy_r1' AND author_masked = 'dum***') = 3,
    'v_content_comments masking != dum*** (left 3 + ***)';
  ASSERT (SELECT max(like_count) FROM analytics.v_content_comments WHERE short_code = 'dummy_r1') = 7,
    'v_content_comments max like_count != 7';

  -- 이력은 고정과 무관하게 전량 보존 (as-of·추이 참조용)
  ASSERT (SELECT count(*) FROM analytics.v_content_metric_snapshots WHERE short_code LIKE 'dummy_%') = 11,
    'v_content_metric_snapshots dummy rows != 11';
  ASSERT (SELECT count(*) FROM analytics.v_content_metric_snapshots WHERE short_code = 'dummy_r1') = 3,
    'v_content_metric_snapshots dummy_r1 snapshots != 3 (구/중/신)';
  ASSERT (SELECT views FROM analytics.v_content_metric_snapshots
          WHERE short_code = 'dummy_r1' ORDER BY captured_at ASC LIMIT 1) = 10000,
    'v_content_metric_snapshots dummy_r1 old views != 10000';
  ASSERT (SELECT views FROM analytics.v_content_metric_snapshots
          WHERE short_code = 'dummy_r1' ORDER BY captured_at DESC LIMIT 1) = 11000,
    'v_content_metric_snapshots dummy_r1 new views != 11000';
  -- 스냅샷 뷰 hype는 captured_at 기준 신선도 — 피드 축 캡(1.0) × 0.5^(3/7) → 91 (픽스처 주석 ②)
  ASSERT (SELECT hype_score FROM analytics.v_content_metric_snapshots WHERE short_code = 'dummy_hype_f') = 91,
    'v_content_metric_snapshots hype 피드(as-of): 기대 91 != ' ||
    (SELECT hype_score FROM analytics.v_content_metric_snapshots WHERE short_code = 'dummy_hype_f');
  ASSERT (SELECT hype_score FROM analytics.v_content_metric_snapshots WHERE short_code = 'dummy_hype_rn') IS NULL,
    'v_content_metric_snapshots 릴스 views NULL: hype_score가 NULL이 아님';

  -- 고정 경과일은 app_setting으로 런타임 조정 (3→4일이면 dummy_r1 고정이 06-05 스냅샷으로 이동)
  INSERT INTO app_setting(key, value) VALUES ('analytics.metric-pin-days', '4')
  ON CONFLICT (key) DO UPDATE SET value = '4';
  ASSERT (SELECT views FROM analytics.v_contents WHERE short_code = 'dummy_r1') = 11000,
    'v_contents dummy_r1 pin-days=4 views != 11000 (설정 오버라이드)';
END $$;
