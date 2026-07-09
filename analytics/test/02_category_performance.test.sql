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
