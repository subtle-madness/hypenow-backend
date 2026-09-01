-- 유사 추천 F&B 개방 — 카테고리 스탯·피어 뷰 축 인지화 (스펙 2026-09-01-similar-influencer-fnb-axis).
--
-- account_category_stats: 뷰티 게이트(is_beauty IS TRUE) 제거 + axis 컬럼(맨 끝, 어휘 파생 —
-- 어휘 밖 main_category는 is_beauty 폴백, 운영 실측 0건이라 이론 방어). 같은 이름을 유지하되
-- 구 소비자별 노출은 다음과 같다(스펙 §3-1) — main_group은 축을 가로질러 중복되지 않는다
-- (대분류가 축을 결정)이라 라벨 충돌은 없고, 남는 것은 "행이 늘어난다"는 효과뿐이다:
--   · 계정 카피 잡 2곳(AccountAnalysisJob·ClaudeBurstRunner): 행 추가로 F&B 카테고리가 프롬프트
--     컨텍스트에 유입된다 — 스펙 §3-1이 의도한 개선. 카피는 stale 주기 재생성이라 즉시 영향도 없다.
--   · 구 was 믹스 CTE(findSimilarHandles의 my_shares·cand_mix): 무해가 아니라 **수용된 변동**이다.
--     혼합 계정의 셰어 분모가 롤링 창(수 분) 동안 양축 합산으로 계산될 수 있어 유사도 점수가
--     미세하게 흔들린다(오류는 아님). 신 was 코드는 같은 릴리스에서 axis 필터로 전환되므로
--     창이 닫히면 사라진다 — 스펙 §3-1이 명시 수용.
--
-- account_peer_axis_stats: V39 body를 계정×축으로 확장(신설). 피어 그룹·퍼센타일·중앙값 전부에
-- axis 파티션 추가. gmed(전역 중앙값 ER)는 base가 전 계정을 양축에 싣기 때문에 축별로 갈라도
-- 값이 동일하다 — 아래 뷰티 투영 동치의 근거.
--
-- account_peer_stats(구 이름): axis='beauty' 투영으로 재정의. 행이 계정당 1→2가 되는 축 뷰를
-- 같은 이름으로 두면 롤링 중 구 findSimilarHandles의 peers CTE에 핸들이 중복돼 유사 목록이
-- 깨진다 — 투영이 expand, 구 이름 제거는 다음 릴리스의 contract 판단.
-- allow-destructive: 뷰 재정의 — DROP 직후 같은 마이그레이션 트랜잭션 안에서 재생성해 참조
--   공백이 없고, 신 소비자(was 유사 추천)는 같은 릴리스로 나간다
DROP VIEW account_peer_stats;
DROP VIEW account_category_stats;

CREATE VIEW account_category_stats AS
SELECT s.account_handle,
       COALESCE(t.main_label, a.main_category) AS main_group,
       count(*)                                AS content_count,
       COALESCE(t.axis, CASE WHEN a.is_beauty THEN 'beauty' ELSE 'fnb' END) AS axis
FROM account_content_series s
JOIN content_analyses a ON a.short_code = s.short_code
LEFT JOIN (SELECT DISTINCT main_value, main_label, axis FROM beauty_taxonomy) t
       ON t.main_value = a.main_category
WHERE a.main_category IS NOT NULL
GROUP BY s.account_handle, COALESCE(t.main_label, a.main_category),
         COALESCE(t.axis, CASE WHEN a.is_beauty THEN 'beauty' ELSE 'fnb' END);

COMMENT ON VIEW account_category_stats IS
  '계정 카테고리 믹스 — 최근 N개 윈도우 × 캡션 대분류 × 축(2026-09-01). 미러 아님(analysis DB 파생 뷰).';

CREATE VIEW account_peer_axis_stats AS
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
),
gmed AS (
  SELECT axis, percentile_cont(0.5) WITHIN GROUP (ORDER BY avg_er_pct) AS global_median_er_pct
  FROM base
  GROUP BY axis
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
       round(g.global_median_er_pct::numeric, 1) AS global_median_er_pct
FROM base b
JOIN med m ON m.axis = b.axis AND m.peer_category = b.peer_category
          AND m.follower_bucket = b.follower_bucket
JOIN gmed g ON g.axis = b.axis
WINDOW peer AS (PARTITION BY b.axis, b.peer_category, b.follower_bucket);

COMMENT ON VIEW account_peer_axis_stats IS
  '피어(축×주 카테고리×팔로워 버킷) 퍼센타일 + 중앙값 ER (2026-09-01). 미러 아님.';

CREATE VIEW account_peer_stats AS
SELECT handle, peer_category, follower_bucket, peer_size,
       top_pct_views, top_pct_er, top_pct_likes, top_pct_comments,
       top_pct_ad_views, top_pct_ad_er, top_pct_ad_likes, top_pct_ad_comments,
       peer_median_er_pct, global_median_er_pct
FROM account_peer_axis_stats
WHERE axis = 'beauty';

COMMENT ON VIEW account_peer_stats IS
  '구 이름 호환 — account_peer_axis_stats의 뷰티 투영(2026-09-01). 신규 소비자는 축 뷰를 쓸 것.';
