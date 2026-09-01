-- account_peer_axis_stats gmed 플랜 병리 수리 (2026-09-01 운영 장애).
--
-- V20260901060734의 gmed(GROUP BY axis) + JOIN gmed g ON g.axis = b.axis 형태는 플래너가
-- CTE 행 수를 오추정(축 필터 후 est 121 vs 실제 12,055)하면 집계를 안쪽에 둔 네스티드 루프가
-- 되어, base 전 행(24,110)에 대한 percentile_cont가 바깥 행마다 재실행된다 — 운영 실측
-- 12,055회 × 3.8ms ≈ 46초(유사 추천 API 전면 타임아웃, 병렬 여부 무관·양축 공통).
--
-- 수리: gmed를 비상관 스칼라 서브쿼리(InitPlan)로 — InitPlan은 추정과 무관하게 정확히 1회
-- 평가된다. 값은 불변: 원 주석대로 base가 전 계정을 양축에 싣기 때문에 축별 gmed가 서로
-- 동일했다 → 한 축 슬라이스('beauty')의 중앙값이 곧 전역 중앙값이다. 컬럼 목록·순서 동일이라
-- CREATE OR REPLACE로 교체(의존 뷰 account_peer_stats 무영향). 운영 실측 46,487ms → 716ms.
CREATE OR REPLACE VIEW account_peer_axis_stats AS
WITH cat AS (
  SELECT DISTINCT ON (account_handle, axis) account_handle, axis, main_group
  FROM account_category_stats
  ORDER BY account_handle, axis, content_count DESC, main_group
),
ad AS (
  SELECT s.account_handle,
         round(avg(s.views) FILTER (WHERE s.views > 0))::bigint                            AS ad_avg_views,
         round(avg((s.likes + s.comments)::numeric / NULLIF(su.followers, 0)) * 100, 1)    AS ad_avg_er_pct,
         round(avg(s.likes))::bigint                                                        AS ad_avg_likes,
         round(avg(s.comments))::bigint                                                     AS ad_avg_comments
  FROM account_content_series s
  JOIN content_analyses an ON an.short_code = s.short_code AND an.ad_type = 'sponsored'
  JOIN account_summaries su ON su.handle = s.account_handle
  GROUP BY s.account_handle
),
base AS (
  SELECT su.handle, ax.axis,
         COALESCE(c.main_group, '미분류') AS peer_category,
         CASE WHEN su.followers IS NULL   THEN '미상'
              WHEN su.followers >= 500000 THEN '50만+'
              WHEN su.followers >= 100000 THEN '10만-50만'
              WHEN su.followers >=  50000 THEN '5만-10만'
              WHEN su.followers >=  10000 THEN '1만-5만'
              ELSE '1만 미만' END          AS follower_bucket,
         su.avg_views, su.avg_er_pct, su.avg_likes, su.avg_comments,
         ad.ad_avg_views, ad.ad_avg_er_pct, ad.ad_avg_likes, ad.ad_avg_comments
  FROM account_summaries su
  CROSS JOIN (VALUES ('beauty'), ('fnb')) AS ax(axis)
  LEFT JOIN cat c ON c.account_handle = su.handle AND c.axis = ax.axis
  LEFT JOIN ad   ON ad.account_handle = su.handle
),
med AS (
  SELECT axis, peer_category, follower_bucket,
         percentile_cont(0.5) WITHIN GROUP (ORDER BY avg_er_pct) AS peer_median_er_pct
  FROM base
  GROUP BY axis, peer_category, follower_bucket
)
SELECT b.handle, b.axis, b.peer_category, b.follower_bucket,
       count(*) OVER peer AS peer_size,
       CASE WHEN b.avg_views IS NOT NULL THEN round((percent_rank() OVER
         (PARTITION BY b.axis, b.peer_category, b.follower_bucket, (b.avg_views IS NULL)
          ORDER BY b.avg_views DESC) * 100)::numeric)::int END       AS top_pct_views,
       CASE WHEN b.avg_er_pct IS NOT NULL THEN round((percent_rank() OVER
         (PARTITION BY b.axis, b.peer_category, b.follower_bucket, (b.avg_er_pct IS NULL)
          ORDER BY b.avg_er_pct DESC) * 100)::numeric)::int END      AS top_pct_er,
       CASE WHEN b.avg_likes IS NOT NULL THEN round((percent_rank() OVER
         (PARTITION BY b.axis, b.peer_category, b.follower_bucket, (b.avg_likes IS NULL)
          ORDER BY b.avg_likes DESC) * 100)::numeric)::int END       AS top_pct_likes,
       CASE WHEN b.avg_comments IS NOT NULL THEN round((percent_rank() OVER
         (PARTITION BY b.axis, b.peer_category, b.follower_bucket, (b.avg_comments IS NULL)
          ORDER BY b.avg_comments DESC) * 100)::numeric)::int END    AS top_pct_comments,
       CASE WHEN b.ad_avg_views IS NOT NULL THEN round((percent_rank() OVER
         (PARTITION BY b.axis, b.peer_category, b.follower_bucket, (b.ad_avg_views IS NULL)
          ORDER BY b.ad_avg_views DESC) * 100)::numeric)::int END    AS top_pct_ad_views,
       CASE WHEN b.ad_avg_er_pct IS NOT NULL THEN round((percent_rank() OVER
         (PARTITION BY b.axis, b.peer_category, b.follower_bucket, (b.ad_avg_er_pct IS NULL)
          ORDER BY b.ad_avg_er_pct DESC) * 100)::numeric)::int END   AS top_pct_ad_er,
       CASE WHEN b.ad_avg_likes IS NOT NULL THEN round((percent_rank() OVER
         (PARTITION BY b.axis, b.peer_category, b.follower_bucket, (b.ad_avg_likes IS NULL)
          ORDER BY b.ad_avg_likes DESC) * 100)::numeric)::int END    AS top_pct_ad_likes,
       CASE WHEN b.ad_avg_comments IS NOT NULL THEN round((percent_rank() OVER
         (PARTITION BY b.axis, b.peer_category, b.follower_bucket, (b.ad_avg_comments IS NULL)
          ORDER BY b.ad_avg_comments DESC) * 100)::numeric)::int END AS top_pct_ad_comments,
       round(m.peer_median_er_pct::numeric, 1)   AS peer_median_er_pct,
       round((SELECT percentile_cont(0.5) WITHIN GROUP (ORDER BY avg_er_pct)
              FROM base WHERE axis = 'beauty')::numeric, 1) AS global_median_er_pct
FROM base b
JOIN med m ON m.axis = b.axis AND m.peer_category = b.peer_category
          AND m.follower_bucket = b.follower_bucket
WINDOW peer AS (PARTITION BY b.axis, b.peer_category, b.follower_bucket);

COMMENT ON VIEW account_peer_axis_stats IS
  '피어(축×주 카테고리×팔로워 버킷) 퍼센타일 + 중앙값 ER (2026-09-01, gmed InitPlan). 미러 아님.';
