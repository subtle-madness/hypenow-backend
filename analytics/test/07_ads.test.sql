DO $$
BEGIN
  -- 광고 표기 콘텐츠 = c2 = 1건
  ASSERT (SELECT content_count FROM analytics.v_ad_performance WHERE ad_marked = true)  = 1, 'ad count != 1';
  ASSERT (SELECT content_count FROM analytics.v_ad_performance WHERE ad_marked = false) = 4, 'non-ad count != 4';
  -- 전체 광고 비율 = 1/5 = 0.2
  ASSERT (SELECT ad_ratio FROM analytics.v_ad_ratio) = 0.2000, 'ad_ratio != 0.2';
END $$;
