-- 그룹 7: 광고/협찬
-- 광고 vs 비광고 성과 비교
CREATE OR REPLACE VIEW analytics.v_ad_performance AS
SELECT
  ad_marked,
  count(*)                        AS content_count,
  round(avg(engagement_rate), 4)  AS avg_engagement_rate,
  round(avg(likes), 1)            AS avg_likes,
  round(avg(views), 1)            AS avg_views
FROM analytics.v_content_performance
GROUP BY ad_marked;

-- 전체 광고 표기 비율
CREATE OR REPLACE VIEW analytics.v_ad_ratio AS
SELECT
  round(avg(CASE WHEN ad_marked THEN 1 ELSE 0 END)::numeric, 4) AS ad_ratio,
  count(*)                                                      AS total_content
FROM analytics.v_content_performance;
