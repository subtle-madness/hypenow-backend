-- 그룹 5: 업로드 타이밍 ↔ (3일 시점) 성과. 시각은 KST 기준.
-- dow: ISO 요일 (1=월 ... 7=일)
CREATE OR REPLACE VIEW analytics.v_timing_performance AS
SELECT
  extract(isodow FROM uploaded_at AT TIME ZONE 'Asia/Seoul')::int AS dow,
  extract(hour   FROM uploaded_at AT TIME ZONE 'Asia/Seoul')::int AS hour,
  count(*)                       AS content_count,
  round(avg(engagement_rate), 4) AS avg_engagement_rate
FROM analytics.v_content_performance
GROUP BY 1, 2;
