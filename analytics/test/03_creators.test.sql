DO $$
BEGIN
  -- 팔로워 구간
  ASSERT (SELECT tier FROM analytics.v_follower_tier WHERE username='dummy_micro') = 'micro', 'micro tier wrong';
  ASSERT (SELECT tier FROM analytics.v_follower_tier WHERE username='dummy_mid')   = 'mid',   'mid tier wrong';
  ASSERT (SELECT tier FROM analytics.v_follower_tier WHERE username='dummy_macro') = 'macro', 'macro tier wrong';
  -- 계정별 성과
  ASSERT (SELECT content_count FROM analytics.v_creator_performance WHERE owner_username='dummy_micro') = 2, 'micro content_count != 2';
  ASSERT (SELECT avg_engagement_rate FROM analytics.v_creator_performance WHERE owner_username='dummy_micro') = 0.0880, 'micro avg ER != 0.088';
  -- 오버퍼폼: dummy_over가 micro tier 중앙값 초과
  ASSERT (SELECT overperforms FROM analytics.v_creator_overperformance WHERE owner_username='dummy_over') = true, 'dummy_over should overperform';
  ASSERT (SELECT overperforms FROM analytics.v_creator_overperformance WHERE owner_username='dummy_micro') = false, 'dummy_micro should not overperform';
END $$;

DO $$
BEGIN
  -- micro 구간: 크리에이터 2명, 콘텐츠 3건, 평균 ER 0.1565
  ASSERT (SELECT creator_count FROM analytics.v_tier_distribution WHERE tier='micro') = 2, 'micro creator_count != 2';
  ASSERT (SELECT content_count FROM analytics.v_tier_distribution WHERE tier='micro') = 3, 'micro content_count != 3';
  ASSERT (SELECT avg_engagement_rate FROM analytics.v_tier_distribution WHERE tier='micro') = 0.1565, 'micro avg ER != 0.1565';
END $$;
