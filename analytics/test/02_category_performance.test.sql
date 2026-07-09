DO $$
BEGIN
  -- main_group B 집계 (subcategory/keyword 롤업 전체)
  ASSERT (SELECT content_count FROM analytics.v_category_performance
          WHERE main_group='B' AND subcategory='(all)' AND keyword='(all)') = 3, 'B count != 3';
  ASSERT (SELECT avg_engagement_rate FROM analytics.v_category_performance
          WHERE main_group='B' AND subcategory='(all)' AND keyword='(all)') = 0.1107, 'B avg ER != 0.1107';
  -- 발굴 키워드 glow (main_group B) = c3,c4 = 2건
  ASSERT (SELECT content_count FROM analytics.v_category_performance
          WHERE main_group='B' AND subcategory='glow_sub' AND keyword='glow') = 2, 'glow count != 2';
END $$;

DO $$
BEGIN
  -- 중앙값 ER (main_group B) = 0.066
  ASSERT (SELECT median_engagement_rate FROM analytics.v_category_performance
          WHERE main_group='B' AND subcategory='(all)' AND keyword='(all)') = 0.0660, 'B median != 0.066';
  -- category 레벨 롤업 (main_group NULL) = 전체 5건
  ASSERT (SELECT content_count FROM analytics.v_category_performance
          WHERE category_id=999 AND main_group IS NULL) = 5, 'category rollup != 5';
  -- 랭킹: 전체 1위 = dummy_c4
  ASSERT (SELECT short_code FROM analytics.v_content_ranking WHERE rank_overall=1) = 'dummy_c4', 'overall #1 not c4';
  -- 랭킹: main_group B 1위 = dummy_c4
  ASSERT (SELECT short_code FROM analytics.v_content_ranking WHERE main_group='B' AND rank_in_main_group=1) = 'dummy_c4', 'B #1 not c4';
END $$;
