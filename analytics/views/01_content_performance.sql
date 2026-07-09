-- 그룹 1: 콘텐츠 성과 (참여율 및 조회수 대비 비율)
CREATE OR REPLACE VIEW analytics.v_content_performance AS
SELECT
  m.*,
  round((m.likes + m.comments_count)::numeric / NULLIF(m.followers, 0), 4)      AS engagement_rate,
  round(m.likes::numeric          / NULLIF(m.video_play_count, 0), 4)           AS like_view_rate,
  round(m.comments_count::numeric / NULLIF(m.video_play_count, 0), 4)           AS comment_view_rate
FROM analytics.v_content_metrics m;
