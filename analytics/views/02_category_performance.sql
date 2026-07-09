-- 그룹 2: 분류별 집계 (category > main_group > subcategory > keyword 롤업)
-- GROUPING SETS로 4개 레벨 제공. 롤업된 하위 레벨은 '(all)'로 표기. 평균+중앙값 ER.
-- 컬럼 순서 변경(category_id 추가)으로 CREATE OR REPLACE 불가 -> DROP 후 재생성.
DROP VIEW IF EXISTS analytics.v_category_performance;
CREATE VIEW analytics.v_category_performance AS
SELECT
  category_id,
  main_group,
  COALESCE(subcategory, '(all)')       AS subcategory,
  COALESCE(discovery_keyword, '(all)') AS keyword,
  count(*)                             AS content_count,
  round(avg(engagement_rate), 4)       AS avg_engagement_rate,
  round(percentile_cont(0.5) WITHIN GROUP (ORDER BY engagement_rate)::numeric, 4) AS median_engagement_rate,
  round(avg(likes), 1)                 AS avg_likes,
  round(avg(views), 1)                 AS avg_views
FROM analytics.v_content_performance
GROUP BY GROUPING SETS (
  (category_id),
  (category_id, main_group),
  (category_id, main_group, subcategory),
  (category_id, main_group, subcategory, discovery_keyword)
);

-- 참여율 랭킹 (전체 / main_group 내). rank_overall=1 이 최고 참여율 콘텐츠.
CREATE OR REPLACE VIEW analytics.v_content_ranking AS
SELECT
  category_id,
  main_group,
  subcategory,
  discovery_keyword,
  short_code,
  owner_username,
  engagement_rate,
  likes,
  views,
  rank() OVER (ORDER BY engagement_rate DESC NULLS LAST)                        AS rank_overall,
  rank() OVER (PARTITION BY main_group ORDER BY engagement_rate DESC NULLS LAST) AS rank_in_main_group
FROM analytics.v_content_performance;
