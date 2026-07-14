-- 서빙 뷰 기대값. 시드 근거:
--   v_accounts 2행 (dummy_a followers=5500 최신)
--   v_contents: 상세 있는 콘텐츠만 (4건 전부 상세 있음) = 4행
--     hype_score: 릴스=views → dummy_r1=11000, dummy_r2=7000, dummy_r3=40000
--                 피드=likes+comments → dummy_f1=2000+100=2100
--   content_type 소문자 매핑: REELS→reels, FEED→feed
--   v_content_comments 3행, author_masked = left(writer,3)||'***' → dummy_fan1→dum***
--   v_content_metric_snapshots: 스냅샷 전체 = 6행 (9101 3행 + 나머지 3행)
--     dummy_r1 구=10000/신=11000 (릴스: hype=views), dummy_f1 = 2000+100=2100 (피드: hype=likes+comments)
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM analytics.v_accounts WHERE handle LIKE 'dummy_%') = 2,
    'v_accounts dummy rows != 2';
  ASSERT (SELECT followers FROM analytics.v_accounts WHERE handle = 'dummy_a') = 5500,
    'v_accounts dummy_a followers != 5500';
  ASSERT (SELECT display_name FROM analytics.v_accounts WHERE handle = 'dummy_a') = '더미 에이',
    'v_accounts dummy_a display_name mismatch';

  ASSERT (SELECT count(*) FROM analytics.v_contents WHERE short_code LIKE 'dummy_%') = 4,
    'v_contents dummy rows != 4';
  ASSERT (SELECT hype_score FROM analytics.v_contents WHERE short_code = 'dummy_r1') = 11000,
    'v_contents dummy_r1 hype_score != views 11000';
  ASSERT (SELECT hype_score FROM analytics.v_contents WHERE short_code = 'dummy_f1') = 2100,
    'v_contents dummy_f1 hype_score != likes+comments 2100';
  ASSERT (SELECT content_type FROM analytics.v_contents WHERE short_code = 'dummy_f1') = 'feed',
    'v_contents dummy_f1 content_type != feed (lowercase)';
  ASSERT (SELECT account_handle FROM analytics.v_contents WHERE short_code = 'dummy_r3') = 'dummy_b',
    'v_contents dummy_r3 account_handle != dummy_b';

  ASSERT (SELECT count(*) FROM analytics.v_content_comments WHERE short_code = 'dummy_r1') = 3,
    'v_content_comments dummy_r1 rows != 3';
  ASSERT (SELECT count(*) FROM analytics.v_content_comments
          WHERE short_code = 'dummy_r1' AND author_masked = 'dum***') = 3,
    'v_content_comments masking != dum*** (left 3 + ***)';
  ASSERT (SELECT max(like_count) FROM analytics.v_content_comments WHERE short_code = 'dummy_r1') = 7,
    'v_content_comments max like_count != 7';

  ASSERT (SELECT count(*) FROM analytics.v_content_metric_snapshots WHERE short_code LIKE 'dummy_%') = 6,
    'v_content_metric_snapshots dummy rows != 6';
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
END $$;
