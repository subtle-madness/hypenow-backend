-- 그룹 10: 인플루언서 상세 (비LLM) — celfit-front AccountReport의 결정 지표.
-- 산식 정본: celfit-front scripts/real-data-pipeline/parse_accounts_recent.py
-- (스펙: docs/superpowers/specs/2026-07-13-c1-account-detail-design.md §3).
-- 서빙 뷰 3종은 미러 1:1 — 컬럼 이름·순서 = V10 DDL = contract record.
-- 신 스키마 이식(2026-07-17 스펙): 밑판 소스만 교체, ad_marked는 릴스 is_paid_partnership
-- 기반이라 sponsored 지표는 릴스 유료 협찬만 잡힌다(피드 광고는 B4 캡션 분류가 대체 소스).

-- 밑판 (미러 안 함): 윈도우 행 + 팔로워. 프로필 없는 계정은 서빙에서 제외 (INNER JOIN 의도 — 프론트가 팔로워를 요구).
CREATE OR REPLACE VIEW analytics.v_account_recent AS
SELECT r.*, p.followers AS profile_followers
FROM analytics.v_recent_content r
JOIN analytics.v_base_profile p ON p.username = r.owner_username;

-- 계정 1행 요약.
-- 기준 지표(metric) 폴백: 조회수 있는 게시물이 max(3, n/2) 미만이면 좋아요 기준 (프론트 상수 — 키로 빼지 않음).
-- 트렌드/광고 비교는 metric 값 > 0인 게시물만 (피드 views NULL은 자연 제외).
-- avg_hype_score: 창 콘텐츠 hype_score(02_serving 함수, now() 신선도 — v_contents와 동일) 단순 평균.
-- 점수 불가(hype NULL) 콘텐츠는 avg가 자연 제외, 전무하면 NULL (스펙 2026-07-29-influencer-avg-hype-score).
CREATE OR REPLACE VIEW analytics.v_account_summaries AS
WITH cfg AS (
  SELECT COALESCE((SELECT value::numeric FROM app_setting
                   WHERE key = 'analytics.trend-threshold'), 0.15) AS trend_threshold
),
win AS (
  SELECT owner_username, content_id, uploaded_at, content_type, likes, comments_count, views, ad_marked,
         profile_followers AS followers,
         row_number() OVER (PARTITION BY owner_username ORDER BY uploaded_at ASC, content_id ASC) AS seq,
         count(*)     OVER (PARTITION BY owner_username)                                          AS n
  FROM analytics.v_account_recent
),
base AS (
  SELECT owner_username,
         max(followers)                                     AS followers,
         count(*)                                           AS analyzed_count,
         count(*) FILTER (WHERE views > 0)                  AS views_count,
         round(avg(views) FILTER (WHERE views > 0))::bigint AS avg_views,
         round(avg((likes + comments_count)::numeric / NULLIF(followers, 0)) * 100, 1) AS avg_er_pct,
         round(avg(likes))::bigint                          AS avg_likes,
         round(avg(comments_count))::bigint                 AS avg_comments,
         round(avg(analytics.hype_score(lower(content_type), views, likes, comments_count,
                                        followers,
                                        extract(epoch FROM (now() - uploaded_at)) / 86400.0)))::bigint
                                                            AS avg_hype_score,
         min(uploaded_at)                                   AS first_posted_at,
         max(uploaded_at)                                   AS last_posted_at
  FROM win
  GROUP BY owner_username
),
metric AS (
  SELECT owner_username,
         CASE WHEN views_count >= GREATEST(3, analyzed_count / 2) THEN 'views' ELSE 'likes' END AS metric
  FROM base
),
mrow AS (
  SELECT w.*, CASE WHEN m.metric = 'views' THEN w.views ELSE w.likes END AS mval
  FROM win w
  JOIN metric m USING (owner_username)
),
-- 올린 순 앞 절반(floor(n/2)) vs 뒤 절반(나머지 — 홀수 중앙은 뒤에 포함). 절반 판정 후 metric>0만 평균.
trend AS (
  SELECT owner_username,
         avg(mval) FILTER (WHERE seq <= n / 2 AND mval > 0) AS older_raw,
         avg(mval) FILTER (WHERE seq >  n / 2 AND mval > 0) AS newer_raw
  FROM mrow
  GROUP BY owner_username
),
ads AS (
  SELECT owner_username,
         count(*) FILTER (WHERE ad_marked)                   AS sponsored_count,
         avg(mval)  FILTER (WHERE NOT ad_marked AND mval > 0) AS organic_raw,
         avg(mval)  FILTER (WHERE ad_marked AND mval > 0)     AS ad_raw,
         count(*)   FILTER (WHERE NOT ad_marked AND mval > 0) AS comparison_organic_count,
         count(*)   FILTER (WHERE ad_marked AND mval > 0)     AS comparison_ad_count,
         max(uploaded_at) FILTER (WHERE ad_marked)            AS last_ad_posted_at
  FROM mrow
  GROUP BY owner_username
)
SELECT
  b.owner_username AS handle,
  b.followers,
  p.follows_count,
  p.posts_count,
  p.biography,
  b.analyzed_count,
  b.views_count,
  m.metric,
  b.avg_views,
  round(b.avg_views::numeric / NULLIF(b.followers, 0), 1) AS views_per_follower,
  b.avg_er_pct,
  b.avg_likes,
  b.avg_comments,
  CASE
    WHEN t.older_raw > 0 AND t.newer_raw > 0 THEN
      CASE WHEN t.newer_raw / t.older_raw - 1 >  cfg.trend_threshold THEN 'up'
           WHEN t.newer_raw / t.older_raw - 1 < -cfg.trend_threshold THEN 'down'
           ELSE 'flat' END
    ELSE 'flat'
  END AS trend_direction,
  CASE WHEN t.older_raw > 0 AND t.newer_raw > 0
       THEN round((t.newer_raw / t.older_raw - 1) * 100)::int
       ELSE 0 END AS trend_change_pct,
  round(t.older_raw)::bigint AS trend_older_avg,
  round(t.newer_raw)::bigint AS trend_newer_avg,
  a.sponsored_count,
  round(a.organic_raw)::bigint AS organic_avg,
  round(a.ad_raw)::bigint      AS ad_avg,
  CASE WHEN a.organic_raw > 0 AND a.ad_raw IS NOT NULL
       THEN round((1 - a.ad_raw / a.organic_raw) * 100)::int
  END AS ad_drop_pct,
  a.comparison_organic_count,
  a.comparison_ad_count,
  a.last_ad_posted_at,
  b.last_posted_at,
  -- 스팬/(n-1): 연속 간격 평균의 절사 없는 정의 (스펙 §3 — 프론트 절사 평균과 소수점만 다를 수 있음)
  CASE WHEN b.analyzed_count > 1
       THEN round((EXTRACT(EPOCH FROM (b.last_posted_at - b.first_posted_at)) / 86400.0
                   / (b.analyzed_count - 1))::numeric, 1)
  END AS avg_interval_days,
  b.avg_hype_score
FROM base b
JOIN metric m USING (owner_username)
JOIN trend  t USING (owner_username)
JOIN ads    a USING (owner_username)
JOIN analytics.v_base_profile p ON p.username = b.owner_username
CROSS JOIN cfg;

-- 카테고리 믹스는 raw DB를 떠났다 (07-21). 대체 소스인 캡션 분류(content_analyses.main_category)는
-- analysis DB에 있어 여기서 조인할 수 없다 — analysis DB 파생 뷰 account_category_stats(V35)가 정본.
-- 구 스텁 뷰(항상 0행)는 미러 등록부에서도 빠졌으므로 운영에서 정리한다(멱등).
DROP VIEW IF EXISTS analytics.v_account_category_stats;

-- 게시물 시계열 (차트 막대·광고 스트립·최근 콘텐츠 탭 재료. 올린 순 정렬은 was 몫)
-- views NULL(피드) 보존 — "0 = 미공개"는 프론트 표현 규약이라 여기서 변환하지 않는다.
CREATE OR REPLACE VIEW analytics.v_account_content_series AS
SELECT short_code,
       owner_username AS account_handle,
       uploaded_at AS posted_at,
       lower(content_type) AS content_type,
       views,
       likes,
       comments_count AS comments,
       ad_marked AS sponsored
FROM analytics.v_account_recent;
