-- 그룹 2: 분류별 집계 (main_group > subcategory > keyword 롤업)
-- GROUPING SETS로 3개 레벨을 한 뷰에서 제공. 롤업된 하위 레벨은 '(all)'로 표기.
CREATE OR REPLACE VIEW analytics.v_category_performance AS
SELECT
  main_group,
  COALESCE(subcategory, '(all)')       AS subcategory,
  COALESCE(discovery_keyword, '(all)') AS keyword,
  count(*)                             AS content_count,
  round(avg(engagement_rate), 4)       AS avg_engagement_rate,
  round(avg(likes), 1)                 AS avg_likes,
  round(avg(video_play_count), 1)      AS avg_views
FROM analytics.v_content_performance
GROUP BY GROUPING SETS (
  (main_group),
  (main_group, subcategory),
  (main_group, subcategory, discovery_keyword)
);
