-- 리포트 개편(07-27): 피어 그룹(주 카테고리 × 팔로워 버킷) 내 지표 퍼센타일 + 중앙값 ER.
-- analysis DB 파생 뷰(V35 account_category_stats 패턴) — 미러 아님, was가 직접 읽는다.
-- 광고 지표는 캡션 분류 정본(content_analyses.ad_type='sponsored') 기준 (AccountAdCanon과 동일).
-- 버킷 경계는 하드코딩 — analysis DB에는 app_setting이 없고, 경계 변경은 후속 마이그레이션으로
-- (beauty_taxonomy 어휘 수정과 같은 규약).
-- percent_rank 규약: 값 큰 쪽이 0(그룹 1위) → 화면 "상위 X%"는 round(rank*100).
-- NULL 지표는 (지표 IS NULL) 파티션 분리로 순위 모수에서 제외하고 결과도 NULL.
CREATE VIEW account_peer_stats AS
WITH cat AS (
  SELECT DISTINCT ON (account_handle) account_handle, main_group
  FROM account_category_stats
  ORDER BY account_handle, content_count DESC, main_group
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
  SELECT su.handle,
         COALESCE(c.main_group, '미분류') AS peer_category,
         -- 팔로워 NULL은 '1만 미만'으로 새지 않게 별도 버킷('미상')으로 뺀다 — peer_category의
         -- '미분류' 폴백과 대칭.
         CASE WHEN su.followers IS NULL   THEN '미상'
              WHEN su.followers >= 500000 THEN '50만+'
              WHEN su.followers >= 100000 THEN '10만-50만'
              WHEN su.followers >=  50000 THEN '5만-10만'
              WHEN su.followers >=  10000 THEN '1만-5만'
              ELSE '1만 미만' END          AS follower_bucket,
         su.avg_views, su.avg_er_pct, su.avg_likes, su.avg_comments,
         ad.ad_avg_views, ad.ad_avg_er_pct, ad.ad_avg_likes, ad.ad_avg_comments
  FROM account_summaries su
  LEFT JOIN cat c ON c.account_handle = su.handle
  LEFT JOIN ad   ON ad.account_handle = su.handle
),
med AS (
  SELECT peer_category, follower_bucket,
         percentile_cont(0.5) WITHIN GROUP (ORDER BY avg_er_pct) AS peer_median_er_pct
  FROM base
  GROUP BY peer_category, follower_bucket
),
gmed AS (
  SELECT percentile_cont(0.5) WITHIN GROUP (ORDER BY avg_er_pct) AS global_median_er_pct FROM base
)
SELECT b.handle, b.peer_category, b.follower_bucket,
       count(*) OVER peer AS peer_size,
       CASE WHEN b.avg_views IS NOT NULL THEN round((percent_rank() OVER
         (PARTITION BY b.peer_category, b.follower_bucket, (b.avg_views IS NULL)
          ORDER BY b.avg_views DESC) * 100)::numeric)::int END       AS top_pct_views,
       CASE WHEN b.avg_er_pct IS NOT NULL THEN round((percent_rank() OVER
         (PARTITION BY b.peer_category, b.follower_bucket, (b.avg_er_pct IS NULL)
          ORDER BY b.avg_er_pct DESC) * 100)::numeric)::int END      AS top_pct_er,
       CASE WHEN b.avg_likes IS NOT NULL THEN round((percent_rank() OVER
         (PARTITION BY b.peer_category, b.follower_bucket, (b.avg_likes IS NULL)
          ORDER BY b.avg_likes DESC) * 100)::numeric)::int END       AS top_pct_likes,
       CASE WHEN b.avg_comments IS NOT NULL THEN round((percent_rank() OVER
         (PARTITION BY b.peer_category, b.follower_bucket, (b.avg_comments IS NULL)
          ORDER BY b.avg_comments DESC) * 100)::numeric)::int END    AS top_pct_comments,
       CASE WHEN b.ad_avg_views IS NOT NULL THEN round((percent_rank() OVER
         (PARTITION BY b.peer_category, b.follower_bucket, (b.ad_avg_views IS NULL)
          ORDER BY b.ad_avg_views DESC) * 100)::numeric)::int END    AS top_pct_ad_views,
       CASE WHEN b.ad_avg_er_pct IS NOT NULL THEN round((percent_rank() OVER
         (PARTITION BY b.peer_category, b.follower_bucket, (b.ad_avg_er_pct IS NULL)
          ORDER BY b.ad_avg_er_pct DESC) * 100)::numeric)::int END   AS top_pct_ad_er,
       CASE WHEN b.ad_avg_likes IS NOT NULL THEN round((percent_rank() OVER
         (PARTITION BY b.peer_category, b.follower_bucket, (b.ad_avg_likes IS NULL)
          ORDER BY b.ad_avg_likes DESC) * 100)::numeric)::int END    AS top_pct_ad_likes,
       CASE WHEN b.ad_avg_comments IS NOT NULL THEN round((percent_rank() OVER
         (PARTITION BY b.peer_category, b.follower_bucket, (b.ad_avg_comments IS NULL)
          ORDER BY b.ad_avg_comments DESC) * 100)::numeric)::int END AS top_pct_ad_comments,
       round(m.peer_median_er_pct::numeric, 1)   AS peer_median_er_pct,
       round(g.global_median_er_pct::numeric, 1) AS global_median_er_pct
FROM base b
JOIN med m ON m.peer_category = b.peer_category AND m.follower_bucket = b.follower_bucket
CROSS JOIN gmed g
WINDOW peer AS (PARTITION BY b.peer_category, b.follower_bucket);

COMMENT ON VIEW account_peer_stats IS
  '피어(주 카테고리×팔로워 버킷) 퍼센타일 + 중앙값 ER — 리포트 개편(07-27, V39). 미러 아님.';
