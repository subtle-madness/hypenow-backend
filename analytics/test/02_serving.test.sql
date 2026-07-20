-- 서빙 뷰 기대값 — 미러 계약 형태(컬럼 이름·순서)는 구 버전과 동일해야 한다.
DO $$
BEGIN
  -- v_accounts: 뷰티 인플루언서 ∩ 프로필 보유
  ASSERT (SELECT count(*) FROM analytics.v_accounts WHERE handle LIKE 'dummy_%') = 2,
    'v_accounts dummy rows != 2 (a·b만)';
  ASSERT EXISTS (SELECT 1 FROM analytics.v_accounts WHERE handle = 'dummy_a' AND followers = 5500),
    'v_accounts dummy_a followers != 5500';
  ASSERT NOT EXISTS (SELECT 1 FROM analytics.v_accounts WHERE handle IN ('dummy_co','dummy_x','dummy_e')),
    'v_accounts에 모수 제외 대상 존재';

  -- v_contents: +3일 고정(성숙 최이른) + 최신 메타 + 피드 NULL
  ASSERT (SELECT count(*) FROM analytics.v_contents WHERE account_handle LIKE 'dummy_%') = 5,
    'v_contents dummy rows != 5';
  ASSERT (SELECT views FROM analytics.v_contents WHERE short_code = 'dummy_r1') = 11000,
    'v_contents r1 views != 11000 (06-05 성숙 최이른 스냅샷 고정)';
  ASSERT (SELECT likes FROM analytics.v_contents WHERE short_code = 'dummy_r1') = 520,
    'v_contents r1 likes != 520 (고정 스냅샷)';
  ASSERT (SELECT caption FROM analytics.v_contents WHERE short_code = 'dummy_r1') = 'cap r1 v3',
    'v_contents r1 caption != cap r1 v3 (메타는 최신)';
  ASSERT (SELECT thumbnail_url FROM analytics.v_contents WHERE short_code = 'dummy_r1') = 'https://thumb/r1_v3.jpg',
    'v_contents r1 thumbnail != 최신 스냅샷';
  ASSERT (SELECT views FROM analytics.v_contents WHERE short_code = 'dummy_rn') = 100,
    'v_contents rn views != 100 (성숙 스냅샷 없음 → 최신 폴백)';
  ASSERT (SELECT views FROM analytics.v_contents WHERE short_code = 'dummy_f1') IS NULL,
    'v_contents f1 views not null (피드)';
  ASSERT (SELECT original_url FROM analytics.v_contents WHERE short_code = 'dummy_r1')
         = 'https://www.instagram.com/p/dummy_r1/',
    'v_contents r1 original_url mismatch (short_code 합성)';
  ASSERT (SELECT content_type FROM analytics.v_contents WHERE short_code = 'dummy_r1') = 'reels',
    'v_contents r1 content_type != reels (lower)';
  ASSERT (SELECT hype_score FROM analytics.v_contents WHERE short_code = 'dummy_r1') IS NOT NULL,
    'v_contents r1 hype_score is null';

  -- v_content_metric_snapshots: 이력 + 합성 id + 모수
  ASSERT (SELECT count(*) FROM analytics.v_content_metric_snapshots WHERE short_code = 'dummy_r1') = 4,
    'v_content_metric_snapshots r1 rows != 4';
  ASSERT (SELECT count(*) = count(DISTINCT id) FROM analytics.v_content_metric_snapshots
          WHERE short_code LIKE 'dummy_%'),
    'v_content_metric_snapshots 합성 id 중복';
  ASSERT NOT EXISTS (SELECT 1 FROM analytics.v_content_metric_snapshots
                     WHERE short_code IN ('dummy_d1','dummy_r4','dummy_r5')),
    'v_content_metric_snapshots에 모수 제외 대상 존재';

  -- v_content_comments: 마스킹
  ASSERT (SELECT count(*) FROM analytics.v_content_comments WHERE short_code = 'dummy_r1') = 3,
    'v_content_comments r1 rows != 3';
  ASSERT (SELECT count(*) FROM analytics.v_content_comments
          WHERE short_code = 'dummy_r1' AND author_masked = 'dum***') = 3,
    'v_content_comments author_masked != dum***';
END $$;

-- hype_score 튜닝 상수 (app_setting: 반감기·reach·engage·feed 목표) — 인자 순서
--   (type, views, likes, comments, followers, elapsed_days, halflife, reach_mult, engage_target, feed_target)
DO $$
DECLARE
  s7 bigint; base bigint; v_old numeric; v_new numeric;
BEGIN
  -- 반감기: 경과 14일 릴스는 반감기가 클수록 감쇠가 작아 점수 ↑ (나머지 상수는 NULL→기본)
  s7   := analytics.hype_score('reels', 10000, 500, 50, 5000, 14, 7,  NULL, NULL, NULL);
  base := analytics.hype_score('reels', 10000, 500, 50, 5000, 14, 14, NULL, NULL, NULL);
  ASSERT base > s7, format('halflife 14(%s) > 7(%s)', base, s7);
  ASSERT analytics.hype_score('reels',10000,500,50,5000,14,NULL,NULL,NULL,NULL) = base, 'NULL 반감기 → 기본 14';
  ASSERT analytics.hype_score('reels',10000,500,50,5000,14,0,   NULL,NULL,NULL) = base, '0 반감기 → 기본 14';

  -- reach 목표 배수: 낮을수록(도달 쉬움) 점수 ↑, NULL·0 → 기본 3
  ASSERT analytics.hype_score('reels',10000,500,50,5000,14,14, 3,  NULL,NULL)
       > analytics.hype_score('reels',10000,500,50,5000,14,14, 30, NULL,NULL), 'reach_mult 3(신) > 30(구)';
  ASSERT analytics.hype_score('reels',10000,500,50,5000,14,14, NULL,NULL,NULL)
       = analytics.hype_score('reels',10000,500,50,5000,14,14, 3,  NULL,NULL), 'NULL reach_mult → 기본 3';
  ASSERT analytics.hype_score('reels',10000,500,50,5000,14,14, 0,  NULL,NULL)
       = analytics.hype_score('reels',10000,500,50,5000,14,14, 3,  NULL,NULL), '0 reach_mult → 기본 3';

  -- engage 목표: 낮을수록 점수 ↑, NULL·0 → 기본 0.04
  ASSERT analytics.hype_score('reels',10000,500,50,5000,14,14, NULL, 0.04, NULL)
       > analytics.hype_score('reels',10000,500,50,5000,14,14, NULL, 0.12, NULL), 'engage 0.04(신) > 0.12(구)';
  ASSERT analytics.hype_score('reels',10000,500,50,5000,14,14, NULL, NULL, NULL)
       = analytics.hype_score('reels',10000,500,50,5000,14,14, NULL, 0.04, NULL), 'NULL engage → 기본 0.04';

  -- feed 목표: 피드(views NULL)에서 낮을수록 점수 ↑, NULL·0 → 기본 0.035 (참여 낮게 잡아 양쪽 비포화)
  ASSERT analytics.hype_score('feed', NULL, 60, 20, 5000, 14, 14, NULL,NULL, 0.035)
       > analytics.hype_score('feed', NULL, 60, 20, 5000, 14, 14, NULL,NULL, 0.10), 'feed 0.035(신) > 0.10(구)';
  ASSERT analytics.hype_score('feed', NULL, 60, 20, 5000, 14, 14, NULL,NULL, NULL)
       = analytics.hype_score('feed', NULL, 60, 20, 5000, 14, 14, NULL,NULL, 0.035), 'NULL feed → 기본 0.035';

  -- 뷰: v_content_metric_snapshots가 app_setting을 읽어 반영(반감기 ↑ → dummy_r1 점수 합 ↑)
  DELETE FROM app_setting WHERE key = 'analytics.hype-fresh-halflife-days';
  INSERT INTO app_setting(key, value) VALUES ('analytics.hype-fresh-halflife-days', '7');
  v_old := (SELECT sum(hype_score) FROM analytics.v_content_metric_snapshots WHERE short_code = 'dummy_r1');
  UPDATE app_setting SET value = '30' WHERE key = 'analytics.hype-fresh-halflife-days';
  v_new := (SELECT sum(hype_score) FROM analytics.v_content_metric_snapshots WHERE short_code = 'dummy_r1');
  ASSERT v_new > v_old, format('뷰 반감기 30(%s) > 7(%s) — app_setting 미반영', v_new, v_old);
END $$;
