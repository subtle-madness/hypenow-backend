DO $$
BEGIN
  ASSERT (SELECT content_count FROM analytics.v_content_type_performance WHERE content_format='reel') = 4, 'reel count != 4';
  ASSERT (SELECT content_count FROM analytics.v_content_type_performance WHERE content_format='feed') = 1, 'feed count != 1';
  -- 영상 길이 구간별 (15초짜리 c4 포함되는 short 구간 존재)
  ASSERT (SELECT count(*) FROM analytics.v_video_duration_performance) >= 1, 'duration buckets empty';
END $$;
