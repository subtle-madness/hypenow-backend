-- 인플루언서 상세 기대값. dummy_a 윈도우(업로드순): r1(12000)·r2(8000)·f1(NULL)·rn(100).
-- metric='views'(views>0 3개 ≥ max(3, 4/2)), trend: older avg(12000,8000)=10000 / newer 100(f1 NULL 제외)
-- → -99% down. avg_er_pct = avg(583,330,2100,6 각각 /5500)*100 = 13.7.
-- dummy_b: 표본 1 → metric='likes'(views_count 1 < 3), r3가 광고라 organic 표본 0 → ad_drop_pct NULL.
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM analytics.v_account_recent WHERE owner_username LIKE 'dummy_%') = 5,
    'v_account_recent dummy rows != 5';

  ASSERT (SELECT analyzed_count FROM analytics.v_account_summaries WHERE handle = 'dummy_a') = 4,
    'summaries a analyzed_count != 4';
  ASSERT (SELECT followers FROM analytics.v_account_summaries WHERE handle = 'dummy_a') = 5500,
    'summaries a followers != 5500';
  ASSERT (SELECT views_count FROM analytics.v_account_summaries WHERE handle = 'dummy_a') = 3,
    'summaries a views_count != 3';
  ASSERT (SELECT avg_views FROM analytics.v_account_summaries WHERE handle = 'dummy_a') = 6700,
    'summaries a avg_views != 6700';
  ASSERT (SELECT metric FROM analytics.v_account_summaries WHERE handle = 'dummy_a') = 'views',
    'summaries a metric != views';
  ASSERT (SELECT avg_er_pct FROM analytics.v_account_summaries WHERE handle = 'dummy_a') = 13.7,
    'summaries a avg_er_pct != 13.7';
  ASSERT (SELECT trend_direction FROM analytics.v_account_summaries WHERE handle = 'dummy_a') = 'down',
    'summaries a trend != down';
  ASSERT (SELECT trend_change_pct FROM analytics.v_account_summaries WHERE handle = 'dummy_a') = -99,
    'summaries a trend_change_pct != -99';
  ASSERT (SELECT metric FROM analytics.v_account_summaries WHERE handle = 'dummy_b') = 'likes',
    'summaries b metric != likes (views 표본 1 < 3)';
  ASSERT (SELECT sponsored_count FROM analytics.v_account_summaries WHERE handle = 'dummy_b') = 1,
    'summaries b sponsored_count != 1';
  ASSERT (SELECT ad_avg FROM analytics.v_account_summaries WHERE handle = 'dummy_b') = 1000,
    'summaries b ad_avg != 1000 (metric=likes)';
  ASSERT (SELECT ad_drop_pct FROM analytics.v_account_summaries WHERE handle = 'dummy_b') IS NULL,
    'summaries b ad_drop_pct not null (유기 표본 0)';

  -- 카테고리 믹스: 소스 소멸 — 형태 유지 + 항상 0행
  ASSERT (SELECT count(*) FROM analytics.v_account_category_stats) = 0,
    'v_account_category_stats not empty';

  ASSERT (SELECT count(*) FROM analytics.v_account_content_series WHERE account_handle LIKE 'dummy_%') = 5,
    'v_account_content_series dummy rows != 5';
  ASSERT (SELECT sponsored FROM analytics.v_account_content_series WHERE short_code = 'dummy_r3') = true,
    'v_account_content_series r3 sponsored != true';
END $$;
