-- 최근 N개 윈도우 (ARCHITECTURE.md §4-1): 모든 계정 단위 지표의 공통 밑판.
-- N은 app_setting 'analytics.recent-window' (기본 12) — 재배포 없이 런타임 조정.
CREATE OR REPLACE VIEW analytics.v_recent_content AS
WITH ranked AS (
  SELECT
    c.content_id,
    c.short_code,
    c.owner_username,
    c.uploaded_at,
    c.content_type,
    c.category_id,
    c.main_group,
    c.ad_marked,
    d.likes,
    d.comments_count,
    d.views,
    d.video_duration,
    d.media_type,
    row_number() OVER (PARTITION BY c.owner_username ORDER BY c.uploaded_at DESC) AS recency_rank
  FROM analytics.v_base_content c
  JOIN analytics.v_base_detail d USING (content_id)
)
SELECT *
FROM ranked
WHERE recency_rank <= COALESCE(
  (SELECT value::int FROM app_setting WHERE key = 'analytics.recent-window'), 12);
