DO $$
BEGIN
  -- 해시태그 makeup: c1,c3,c4 = 3건
  ASSERT (SELECT content_count FROM analytics.v_hashtag_performance WHERE tag='makeup') = 3, 'makeup count != 3';
  ASSERT (SELECT content_count FROM analytics.v_hashtag_performance WHERE tag='kbeauty') = 2, 'kbeauty count != 2';
  -- 멘션 brand_x: c2 = 1건
  ASSERT (SELECT content_count FROM analytics.v_mention_performance WHERE mention='brand_x') = 1, 'brand_x mention != 1';
  -- 댓글 통계 (dummy_c1 = 9101)
  ASSERT (SELECT comment_count   FROM analytics.v_content_comment_stats WHERE content_id=9101) = 3, 'c1 comment_count != 3';
  ASSERT (SELECT unique_writers  FROM analytics.v_content_comment_stats WHERE content_id=9101) = 2, 'c1 unique_writers != 2';
  ASSERT (SELECT reply_ratio     FROM analytics.v_content_comment_stats WHERE content_id=9101) = 1.0000, 'c1 reply_ratio != 1.0';
END $$;
