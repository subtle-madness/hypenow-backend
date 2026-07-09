-- 그룹 1: 콘텐츠 성과 (참여율 및 조회수 대비 비율)
-- 조회수 = video_play_count, 없으면 videoViewCount 폴백 (스펙 그룹1)
CREATE OR REPLACE VIEW analytics.v_content_performance AS
SELECT
  m.*,
  round((m.likes + m.comments_count)::numeric / NULLIF(m.followers, 0), 4)      AS engagement_rate,
  round(m.likes::numeric          / NULLIF(COALESCE(m.video_play_count, m.video_view_count), 0), 4) AS like_view_rate,
  round(m.comments_count::numeric / NULLIF(COALESCE(m.video_play_count, m.video_view_count), 0), 4) AS comment_view_rate,
  COALESCE(m.video_play_count, m.video_view_count)                              AS views
FROM analytics.v_content_metrics m;
