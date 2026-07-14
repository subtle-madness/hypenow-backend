-- 기준선 뷰 기대값 (시드 손계산 근거는 계획 문서 참조)
DELETE FROM app_setting WHERE key = 'analytics.recent-window';

DO $$
BEGIN
  ASSERT (SELECT count(*) FROM analytics.v_analysis_baseline WHERE short_code LIKE 'dummy_%') = 4,
    'baseline rows != 4';
  ASSERT (SELECT recent12_avg_engagement_rate FROM analytics.v_analysis_baseline WHERE short_code = 'dummy_r1') = 0.0496,
    'dummy_a avg ER != 0.0496';
  ASSERT (SELECT recent12_avg_like_count FROM analytics.v_analysis_baseline WHERE short_code = 'dummy_r1') = 940,
    'dummy_a avg likes != 940';
  ASSERT (SELECT recent_contents_count FROM analytics.v_analysis_baseline WHERE short_code = 'dummy_r1') = 3,
    'dummy_a window count != 3';
  ASSERT (SELECT recent_reels_avg_views FROM analytics.v_analysis_baseline WHERE short_code = 'dummy_r1') = 9000,
    'dummy_a reels avg views != 9000';
  ASSERT (SELECT rank_in_recent_reels FROM analytics.v_analysis_baseline WHERE short_code = 'dummy_r1') = 1,
    'dummy_r1 reels rank != 1';
  ASSERT (SELECT rank_in_recent_reels FROM analytics.v_analysis_baseline WHERE short_code = 'dummy_r2') = 2,
    'dummy_r2 reels rank != 2';
  ASSERT (SELECT rank_in_recent_reels FROM analytics.v_analysis_baseline WHERE short_code = 'dummy_f1') IS NULL,
    'feed reels rank must be NULL';
  ASSERT (SELECT category_sample_size FROM analytics.v_analysis_baseline WHERE short_code = 'dummy_r3') = 3,
    'category sample != 3 (views NULL 제외)';
  ASSERT (SELECT category_top_percentile FROM analytics.v_analysis_baseline WHERE short_code = 'dummy_r3') = 34,
    'dummy_r3 top percentile != 34';
  ASSERT (SELECT category_top_percentile FROM analytics.v_analysis_baseline WHERE short_code = 'dummy_r1') = 67,
    'dummy_r1 top percentile != 67';
END $$;
