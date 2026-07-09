CREATE SCHEMA IF NOT EXISTS analytics;

-- 계정별 최신 프로필
CREATE OR REPLACE VIEW analytics.v_latest_profile AS
SELECT DISTINCT ON (account_id)
  account_id,
  username,
  followers,
  (payload->>'followsCount')::bigint      AS follows,
  (payload->>'postsCount')::bigint        AS posts,
  (payload->>'verified')::boolean         AS verified,
  (payload->>'isBusinessAccount')::boolean AS is_business,
  payload->>'businessCategoryName'        AS business_category
FROM raw_profile
ORDER BY account_id, captured_at DESC, id DESC;

-- 콘텐츠별 최신 상세
CREATE OR REPLACE VIEW analytics.v_latest_detail AS
SELECT DISTINCT ON (content_id)
  content_id,
  likes,
  comments_count,
  video_play_count,
  (payload->>'videoViewCount')::bigint AS video_view_count,
  (payload->>'videoDuration')::numeric AS video_duration,
  payload->>'type'                     AS media_type,
  payload->>'productType'              AS product_type,
  payload->'hashtags'                  AS hashtags,
  payload->'mentions'                  AS mentions,
  jsonb_array_length(CASE WHEN jsonb_typeof(payload->'childPosts')='array' THEN payload->'childPosts' ELSE '[]'::jsonb END) AS child_post_count
FROM raw_post_detail
ORDER BY content_id, captured_at DESC, id DESC;

-- 콘텐츠 팩트 (성과 지표 계산 전 원자료 + 팔로워)
CREATE OR REPLACE VIEW analytics.v_content_metrics AS
SELECT
  c.id AS content_id,
  c.short_code,
  c.content_type,
  c.owner_username,
  c.uploaded_at,
  c.category_id,
  c.main_group,
  c.subcategory,
  c.discovery_keyword,
  c.ad_marked,
  d.likes,
  d.comments_count,
  d.video_play_count,
  d.video_view_count,
  d.video_duration,
  d.media_type,
  d.product_type,
  d.hashtags,
  d.mentions,
  d.child_post_count,
  p.followers,
  p.verified,
  p.is_business,
  p.business_category,
  CASE
    WHEN d.media_type = 'Video'              THEN 'reel'
    WHEN d.media_type IN ('Image','Sidecar') THEN 'feed'
    ELSE 'other'
  END AS content_format
FROM content c
JOIN analytics.v_latest_detail d ON d.content_id = c.id
LEFT JOIN account a              ON a.username = c.owner_username
LEFT JOIN analytics.v_latest_profile p ON p.account_id = a.id;
