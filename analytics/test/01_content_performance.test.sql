DO $$
BEGIN
  -- 참여율 = (likes+comments)/followers
  ASSERT (SELECT engagement_rate FROM analytics.v_content_performance WHERE short_code='dummy_c4') = 0.2250, 'c4 ER wrong';
  ASSERT (SELECT engagement_rate FROM analytics.v_content_performance WHERE short_code='dummy_c1') = 0.1100, 'c1 ER wrong';
  -- 조회수 대비 좋아요율 = likes/views
  ASSERT (SELECT like_view_rate FROM analytics.v_content_performance WHERE short_code='dummy_c1') = 0.0500, 'c1 like/view wrong';
  -- 피드(조회수 없음)는 NULL
  ASSERT (SELECT like_view_rate FROM analytics.v_content_performance WHERE short_code='dummy_c2') IS NULL, 'c2 like/view should be null';
  -- 최고 참여율 콘텐츠는 c4
  ASSERT (SELECT short_code FROM analytics.v_content_performance ORDER BY engagement_rate DESC LIMIT 1) = 'dummy_c4', 'top ER not c4';
END $$;
