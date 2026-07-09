-- 그룹 4: 콘텐츠 타입/형식 비교
-- 릴스 vs 피드
CREATE OR REPLACE VIEW analytics.v_content_type_performance AS
SELECT
  content_format,
  count(*)                        AS content_count,
  round(avg(engagement_rate), 4)  AS avg_engagement_rate,
  round(avg(likes), 1)            AS avg_likes,
  round(avg(views), 1)            AS avg_views
FROM analytics.v_content_performance
GROUP BY content_format;

-- 영상 길이 구간 ↔ 성과 (영상만)
CREATE OR REPLACE VIEW analytics.v_video_duration_performance AS
SELECT
  CASE
    WHEN video_duration < 15 THEN '0-15s'
    WHEN video_duration < 30 THEN '15-30s'
    WHEN video_duration < 60 THEN '30-60s'
    ELSE '60s+'
  END AS duration_bucket,
  count(*)                       AS content_count,
  round(avg(engagement_rate), 4) AS avg_engagement_rate,
  round(avg(views),1)            AS avg_views
FROM analytics.v_content_performance
WHERE video_duration IS NOT NULL
GROUP BY 1;
