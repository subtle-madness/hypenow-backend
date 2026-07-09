-- 그룹 3: 크리에이터/계정
-- 팔로워 구간 경계 (조정 가능): micro < 10000 <= mid < 100000 <= macro
CREATE OR REPLACE VIEW analytics.v_follower_tier AS
SELECT
  account_id,
  username,
  followers,
  CASE
    WHEN followers IS NULL  THEN 'unknown'
    WHEN followers < 10000  THEN 'micro'
    WHEN followers < 100000 THEN 'mid'
    ELSE 'macro'
  END AS tier
FROM analytics.v_latest_profile;

-- 계정별 성과
CREATE OR REPLACE VIEW analytics.v_creator_performance AS
SELECT
  owner_username,
  count(*)                       AS content_count,
  round(avg(engagement_rate), 4) AS avg_engagement_rate,
  round(avg(likes), 1)           AS avg_likes,
  max(followers)                 AS followers
FROM analytics.v_content_performance
GROUP BY owner_username;

-- 오버퍼폼: 같은 팔로워 구간 중앙 ER을 초과하는 계정 (협업 후보)
CREATE OR REPLACE VIEW analytics.v_creator_overperformance AS
WITH creator AS (
  SELECT
    owner_username,
    avg(engagement_rate) AS er,
    max(followers)       AS followers,
    CASE
      WHEN max(followers) IS NULL   THEN 'unknown'
      WHEN max(followers) < 10000  THEN 'micro'
      WHEN max(followers) < 100000 THEN 'mid'
      ELSE 'macro'
    END AS tier
  FROM analytics.v_content_performance
  GROUP BY owner_username
),
tier_median AS (
  SELECT tier, percentile_cont(0.5) WITHIN GROUP (ORDER BY er) AS med
  FROM creator
  GROUP BY tier
)
SELECT
  c.owner_username,
  c.tier,
  round(c.er, 4)  AS avg_engagement_rate,
  round(m.med::numeric, 4) AS tier_median_er,
  (c.er > m.med)  AS overperforms
FROM creator c
JOIN tier_median m USING (tier);
