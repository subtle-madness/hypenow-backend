-- 기준선 기대값. dummy_a 윈도우 = r1(11000)·r2(8000)·f1(NULL)·rn(100) — 지표 핀 기준(v_pinned_metrics).
-- r1은 성숙 최이른 clips(06-05, 11000/520)로 고정 — 랭킹(v_contents)과 동일 스냅샷. captured_at만 최신(메타).
DO $$
BEGIN
  ASSERT (SELECT recent_contents_count FROM analytics.v_analysis_baseline WHERE short_code = 'dummy_r1') = 4,
    'baseline r1 recent_contents_count != 4';
  ASSERT (SELECT recent_reels_count FROM analytics.v_analysis_baseline WHERE short_code = 'dummy_r1') = 3,
    'baseline r1 recent_reels_count != 3 (views 있는 릴스: r1·r2·rn)';
  ASSERT (SELECT recent_reels_avg_views FROM analytics.v_analysis_baseline WHERE short_code = 'dummy_r1') = 6367,
    'baseline r1 recent_reels_avg_views != 6367 (avg(11000,8000,100)=6366.67)';
  ASSERT (SELECT rank_in_recent_reels FROM analytics.v_analysis_baseline WHERE short_code = 'dummy_r1') = 1,
    'baseline r1 rank != 1';
  ASSERT (SELECT rank_in_recent_reels FROM analytics.v_analysis_baseline WHERE short_code = 'dummy_r2') = 2,
    'baseline r2 rank != 2';
  ASSERT (SELECT recent12_avg_like_count FROM analytics.v_analysis_baseline WHERE short_code = 'dummy_r1') = 706,
    'baseline r1 avg_like != 706 (avg(520,300,2000,5)=706.25)';
  ASSERT (SELECT category_top_percentile FROM analytics.v_analysis_baseline WHERE short_code = 'dummy_r1') IS NULL,
    'baseline category_top_percentile not null (소스 소멸 — NULL 상수)';
  ASSERT (SELECT category_avg_views FROM analytics.v_analysis_baseline WHERE short_code = 'dummy_r1') IS NULL,
    'baseline category_avg_views not null';
  ASSERT (SELECT category_sample_size FROM analytics.v_analysis_baseline WHERE short_code = 'dummy_r1') IS NULL,
    'baseline category_sample_size not null';
  ASSERT (SELECT captured_at FROM analytics.v_analysis_baseline WHERE short_code = 'dummy_r1')
         = timestamptz '2026-06-08 12:00:00+09',
    'baseline r1 captured_at != 최신 스냅샷 시각';
END $$;
