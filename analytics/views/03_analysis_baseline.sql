-- 콘텐츠별 기준선 (분석 잡 전용 — 미러 안 함, 분석 시점에 content_analyses로 고정 저장).
-- ER = (likes+comments)/views (노션 확정안). views NULL(피드)은 ER NULL → 평균에서 제외.
-- 조회수 비교 모수(릴스)와 참여 지표 모수(최근 N개 전체)를 분리해 함께 기록한다.
CREATE OR REPLACE VIEW analytics.v_analysis_baseline AS
WITH windowed AS (
  -- captured_at은 최신 수집분 우선 정렬용 (B3 VLM — 썸네일 서명 URL 만료 대응).
  -- v_recent_content에 넣지 않고 여기서 조인하는 이유: star-select 하위 뷰(10번)의 컬럼 위치를 지키기 위함.
  SELECT w.*,
         round((w.likes + w.comments_count)::numeric / NULLIF(w.views, 0), 4) AS er,
         d.captured_at
  FROM analytics.v_recent_content w
  JOIN analytics.v_base_detail d USING (content_id)
),
account_agg AS (
  SELECT owner_username,
         count(*)                                            AS recent_contents_count,
         round(avg(er), 4)                                   AS recent12_avg_engagement_rate,
         round(avg(likes), 0)                                AS recent12_avg_like_count,
         round(avg(comments_count), 0)                       AS recent12_avg_comment_count,
         count(*) FILTER (WHERE lower(content_type) = 'reels' AND views IS NOT NULL) AS recent_reels_count,
         round(avg(views) FILTER (WHERE lower(content_type) = 'reels'), 0)           AS recent_reels_avg_views
  FROM windowed
  GROUP BY owner_username
),
reels_rank AS (
  SELECT content_id,
         rank() OVER (PARTITION BY owner_username ORDER BY views DESC NULLS LAST) AS rank_in_recent_reels
  FROM windowed
  WHERE lower(content_type) = 'reels' AND views IS NOT NULL
),
category_ctx AS (
  SELECT content_id,
         ceil(100 * cume_dist() OVER (PARTITION BY category_id ORDER BY views DESC))::smallint AS category_top_percentile,
         round(avg(views) OVER (PARTITION BY category_id), 0)  AS category_avg_views,
         count(*) OVER (PARTITION BY category_id)              AS category_sample_size
  FROM windowed
  WHERE views IS NOT NULL
)
SELECT
  w.short_code,
  a.recent_reels_avg_views,
  r.rank_in_recent_reels,
  a.recent_reels_count,
  a.recent_contents_count,
  a.recent12_avg_engagement_rate,
  a.recent12_avg_like_count,
  a.recent12_avg_comment_count,
  c.category_top_percentile,
  c.category_avg_views,
  c.category_sample_size,
  w.captured_at
FROM windowed w
JOIN account_agg a USING (owner_username)
LEFT JOIN reels_rank r USING (content_id)
LEFT JOIN category_ctx c USING (content_id);
