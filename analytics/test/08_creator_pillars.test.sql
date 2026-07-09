DO $$
BEGIN
  -- v_creator_card (성과 기둥): micro 구간 = {dummy_micro, dummy_over}
  ASSERT (SELECT content_count FROM analytics.v_creator_card WHERE owner_username='dummy_micro') = 2, 'dummy_micro content_count != 2';
  ASSERT (SELECT avg_engagement_rate FROM analytics.v_creator_card WHERE owner_username='dummy_micro') = 0.0880, 'dummy_micro avg_engagement_rate != 0.088';
  ASSERT (SELECT tier FROM analytics.v_creator_card WHERE owner_username='dummy_micro') = 'micro', 'dummy_micro tier != micro';
  ASSERT (SELECT tier FROM analytics.v_creator_card WHERE owner_username='dummy_over') = 'micro', 'dummy_over tier != micro';

  ASSERT (SELECT er_percentile FROM analytics.v_creator_card WHERE owner_username='dummy_over') = 1.0000, 'dummy_over er_percentile != 1.0';
  ASSERT (SELECT overperform_ratio FROM analytics.v_creator_card WHERE owner_username='dummy_over') = 1.4377, 'dummy_over overperform_ratio != 1.4377';

  ASSERT (SELECT er_percentile FROM analytics.v_creator_card WHERE owner_username='dummy_micro') = 0.0000, 'dummy_micro er_percentile != 0.0';
  ASSERT (SELECT overperform_ratio FROM analytics.v_creator_card WHERE owner_username='dummy_micro') = 0.5623, 'dummy_micro overperform_ratio != 0.5623';
END $$;

DO $$
BEGIN
  -- v_creator_authenticity (진위 기둥): dummy_micro의 유일한 댓글 보유 콘텐츠 = 9101 (댓글 3건)
  ASSERT (SELECT comment_count FROM analytics.v_creator_authenticity WHERE owner_username='dummy_micro') = 3, 'dummy_micro comment_count != 3';
  ASSERT (SELECT unique_writer_ratio FROM analytics.v_creator_authenticity WHERE owner_username='dummy_micro') = 0.6667, 'dummy_micro unique_writer_ratio != 0.6667';
  ASSERT (SELECT early_comment_ratio FROM analytics.v_creator_authenticity WHERE owner_username='dummy_micro') = 0.0000, 'dummy_micro early_comment_ratio != 0.0';
  ASSERT (SELECT generic_comment_ratio FROM analytics.v_creator_authenticity WHERE owner_username='dummy_micro') = 0.6667, 'dummy_micro generic_comment_ratio != 0.6667';
END $$;

DO $$
BEGIN
  -- v_creator_top_contents: dummy_micro 최고 ER 콘텐츠 = dummy_c1 (0.11 > 0.066)
  ASSERT (SELECT short_code FROM analytics.v_creator_top_contents WHERE owner_username='dummy_micro' AND rank_in_creator=1) = 'dummy_c1', 'dummy_micro rank1 short_code != dummy_c1';
END $$;

DO $$
BEGIN
  -- v_creator_comment_samples: 가장 긴 댓글 = 'where to buy' (12자)
  ASSERT (SELECT comment_text FROM analytics.v_creator_comment_samples WHERE owner_username='dummy_micro' AND sample_rank=1) = 'where to buy', 'dummy_micro sample1 comment_text != where to buy';
END $$;
