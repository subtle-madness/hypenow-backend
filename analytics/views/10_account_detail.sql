-- 그룹 10: 인플루언서 상세 (비LLM) — celfit-front AccountReport의 결정 지표.
-- 산식 정본: celfit-front scripts/real-data-pipeline/parse_accounts_recent.py
-- (스펙: docs/superpowers/specs/2026-07-13-c1-account-detail-design.md §3).
-- 서빙 뷰 3종은 미러 1:1 — 컬럼 이름·순서 = V10 DDL = contract record.
-- 신 스키마 이식(2026-07-17 스펙): 밑판 소스만 교체, ad_marked는 릴스 is_paid_partnership
-- 기반이라 sponsored 지표는 릴스 유료 협찬만 잡힌다(피드 광고는 B4 캡션 분류가 대체 소스).

-- 계정 하입 스코어 매핑 함수 (트랙 Z 후속 — 계정 점수 척도 재교정, 2026-07-30. 스펙
-- 2026-07-30-hype-score-v3-decay-after-mapping-design.md §9).
-- 왜 필요한가: avg_hype_score는 창(최근 최대 12개) 콘텐츠 hype_score의 **평균**인데, 콘텐츠 함수가
-- 이미 신선도 감쇠(0.5^(경과일/14))를 적용한 값들을 평균 내다 보니 창 스팬(가장 활발한 계정도
-- 17일 안팎)에 걸쳐 뒤쪽(오래된) 게시물이 깎여 평균 자체가 눌린다 — test 스택 실측 최상위 계정
-- (beauty_linyas2, 창 1.8~16.9일)의 반올림 전 평균이 58.9에 그쳐 0~100 척도인데 상위 40점
-- 구간이 사실상 도달 불가였다. 게시 빈도가 계정 점수를 좌우하는 것 자체는 의도된 동작
-- (hypenow 결정) — 순위는 그대로 두고 척도만 콘텐츠 함수(analytics.hype_score)와 같은 4점
-- 구간선형 매핑으로 재교정한다. 콘텐츠 점수 산식·반감기는 이 변경으로 건드리지 않는다.
-- 앵커는 계정 raw 평균(0점 제외 모수) 분위수로 별도 적합 — 콘텐츠 앵커(Q 기준)와 기준량이
-- 달라 공유 불가. 재산출 절차·정밀값 근거는 analytics/check/hype-anchor-refit.sql 계정 섹션.
-- app_setting 키: analytics.hype-anchor-acct-{p05,p50,p90,p99}(미설정/0이면 COALESCE 기본값 —
-- 단일 소스는 함수 기본값, hype_score와 동일 관용구).
-- raw IS NULL(창 전체 점수 불가 — 기존 동작)은 NULL 유지. raw=0은 10×0/a05=0으로 자연 0점.
CREATE OR REPLACE FUNCTION analytics.hype_account_score(raw numeric) RETURNS bigint
LANGUAGE sql STABLE AS $$
  WITH s AS (
    SELECT
      COALESCE(NULLIF((SELECT value::numeric FROM app_setting WHERE key='analytics.hype-anchor-acct-p05'),0),1.0833)  AS a05,
      COALESCE(NULLIF((SELECT value::numeric FROM app_setting WHERE key='analytics.hype-anchor-acct-p50'),0),12.8333) AS a50,
      COALESCE(NULLIF((SELECT value::numeric FROM app_setting WHERE key='analytics.hype-anchor-acct-p90'),0),31.2000) AS a90,
      COALESCE(NULLIF((SELECT value::numeric FROM app_setting WHERE key='analytics.hype-anchor-acct-p99'),0),44.8600) AS a99
  )
  SELECT CASE
    WHEN raw IS NULL THEN NULL
    ELSE round(
      GREATEST(LEAST(
        CASE
          WHEN raw <= s.a05 THEN 10*raw/NULLIF(s.a05,0)
          WHEN raw <= s.a50 THEN 10 + 35*(raw-s.a05)/NULLIF(s.a50-s.a05,0)
          WHEN raw <= s.a90 THEN 45 + 35*(raw-s.a50)/NULLIF(s.a90-s.a50,0)
          WHEN raw <= s.a99 THEN 80 + 17*(raw-s.a90)/NULLIF(s.a99-s.a90,0)
          ELSE 97 + 3*(raw-s.a99)/NULLIF(s.a99-s.a90,0)
        END, 100), 0)
    )::bigint
  END
  FROM s
$$;

-- 밑판 (미러 안 함): 윈도우 행 + 팔로워. 프로필 없는 계정은 서빙에서 제외 (INNER JOIN 의도 — 프론트가 팔로워를 요구).
CREATE OR REPLACE VIEW analytics.v_account_recent AS
SELECT r.*, p.followers AS profile_followers
FROM analytics.v_recent_content r
JOIN analytics.v_base_profile p ON p.username = r.owner_username;

-- 계정 1행 요약.
-- 기준 지표(metric) 폴백: 조회수 있는 게시물이 max(3, n/2) 미만이면 좋아요 기준 (프론트 상수 — 키로 빼지 않음).
-- 트렌드/광고 비교는 metric 값 > 0인 게시물만 (피드 views NULL은 자연 제외).
-- avg_hype_score: 창 콘텐츠 hype_score(02_serving 함수, now() 신선도 — v_contents와 동일) 단순 평균을
-- analytics.hype_account_score()로 매핑한 값(트랙 Z 후속 — 계정 점수 척도 재교정, 2026-07-30).
-- 평균은 반올림하지 않고 그대로 매핑 함수에 넘긴다(이중 반올림 제거) — 매핑 함수 안에서 최종 round.
-- 점수 불가(hype NULL) 콘텐츠는 avg가 자연 제외, 전무하면 avg 자체가 NULL → 매핑 함수도 NULL 유지
-- (스펙 2026-07-29-influencer-avg-hype-score, 2026-07-30-hype-score-v3-decay-after-mapping §9).
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
         analytics.hype_account_score(avg(analytics.hype_score(lower(content_type), views, likes, comments_count,
                                        followers,
                                        extract(epoch FROM (now() - uploaded_at)) / 86400.0)))
                                                            AS avg_hype_score,
         min(uploaded_at)                                   AS first_posted_at,
         max(uploaded_at)                                   AS last_posted_at,
         -- 통계 왜곡 가드 재료 (스펙 2026-07-30-perf-summary-statistical-guards-design.md §3-1):
         -- 지표별 실질 모수 — analyzed_count는 12로 꽉 차도 조회수 관측은 1건일 수 있다.
         count(*) FILTER (WHERE views IS NOT NULL)::int          AS views_sample_count,
         count(*) FILTER (WHERE likes IS NOT NULL)::int          AS likes_sample_count,
         count(*) FILTER (WHERE comments_count IS NOT NULL)::int AS comments_sample_count,
         count(*) FILTER (WHERE content_type = 'REELS')::int     AS reels_count,
         count(*) FILTER (WHERE content_type = 'FEED')::int      AS feed_count,
         round(percentile_cont(0.5) WITHIN GROUP (ORDER BY views)
               FILTER (WHERE views IS NOT NULL))::bigint    AS median_views,
         -- avg_er_pct와 동일 산식(분모 NULLIF)의 중앙값 — 수준 판정 근거를 median으로 옮기는 게 목적(§3-3).
         -- percentile_cont는 double precision을 반환해 round(numeric,int)와 안 맞으므로 명시 캐스트.
         round((percentile_cont(0.5) WITHIN GROUP (
               ORDER BY (likes + comments_count)::numeric / NULLIF(followers, 0)) * 100)::numeric, 1)
                                                            AS median_er_pct,
         -- 최상위 1건의 조회수 점유율 — 관측(sum) 없거나 0이면 NULL(NULLIF).
         round(max(views)::numeric
               / NULLIF(sum(views) FILTER (WHERE views IS NOT NULL), 0) * 100)::int
                                                            AS top_views_share_pct
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
  b.avg_hype_score,
  b.views_sample_count,
  b.likes_sample_count,
  b.comments_sample_count,
  b.reels_count,
  b.feed_count,
  b.median_views,
  b.median_er_pct,
  b.top_views_share_pct,
  -- 스팬 일수(정수) — extract(day from interval)은 총 일수 성분을 그대로 준다(시:분초 절사).
  extract(day from (b.last_posted_at - b.first_posted_at))::int AS window_span_days
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
