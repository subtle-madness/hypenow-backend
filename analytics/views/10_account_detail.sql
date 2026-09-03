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
-- 2026-08-17 재적합(댓글 가중 1.5 기준 운영 코퍼스 실측, 스펙
-- docs/superpowers/specs/archive/2026-08-17-hype-comment-weight-design.md, 모수 n=4,431).
CREATE OR REPLACE FUNCTION analytics.hype_account_score(raw numeric) RETURNS bigint
LANGUAGE sql STABLE AS $$
  WITH s AS (
    SELECT
      COALESCE(NULLIF((SELECT value::numeric FROM app_setting WHERE key='analytics.hype-anchor-acct-p05'),0),1.1667)  AS a05,
      COALESCE(NULLIF((SELECT value::numeric FROM app_setting WHERE key='analytics.hype-anchor-acct-p50'),0),12.0833) AS a50,
      COALESCE(NULLIF((SELECT value::numeric FROM app_setting WHERE key='analytics.hype-anchor-acct-p90'),0),30.5455) AS a90,
      COALESCE(NULLIF((SELECT value::numeric FROM app_setting WHERE key='analytics.hype-anchor-acct-p99'),0),45.6667) AS a99
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

-- 계정 하입 스코어 소수점 매핑 함수 (2026-07-30 — 콘텐츠 출력 매핑 도입에 따른 계정 앵커 재적합,
-- 스펙 2026-07-30-hype-score-v3-decay-after-mapping-design.md §10). 별도 함수인 이유: 콘텐츠에
-- 출력 매핑(analytics.hype_score_output, 02_serving.sql)이 붙으면서 계정 raw 평균의 입력 기준량
-- 자체가 바뀐다(창 콘텐츠의 hype_score_output(hype_score_raw(...)) 평균 — 기존 avg_hype_raw는
-- 여전히 hype_score()의 정수 평균으로 **값·의미 불변**) — 같은 함수·같은 app_setting 키에 새
-- 앵커를 덮어쓰면 avg_hype_score(bigint, 기존 그대로 유지해야 하는 표시값)가 따라 바뀐다. 그래서
-- hype_account_score는 건드리지 않고, 새 입력 기준량 전용으로 앵커를 다시 적합한 이 함수를 둔다.
-- 앵커는 계정 raw 평균(0점 및 반올림하면 0이 되는 raw<0.5 제외 모수) 분위수로 적합 — 콘텐츠
-- 출력 앵커(랭킹 경로 단일 세트)와도 기준량이 달라 공유 불가. 재산출 절차·정밀값 근거는
-- analytics/check/hype-anchor-refit.sql 계정 섹션(출력 매핑 반영판).
-- app_setting 키: analytics.hype-anchor-acct-precise-{p05,p50,p90,p99}(미설정/0이면 COALESCE
-- 기본값 — 단일 소스는 함수 기본값, hype_account_score·hype_score_output과 동일 관용구).
-- raw IS NULL(창 전체 점수 불가)은 NULL 유지. 최종 반올림은 호출부(v_account_summaries)가
-- round(...,4)로 한다 — 이 함수 자체는 정수로 자르지 않는다(소수점 노출이 목적이므로).
-- 앵커 재적합(2026-07-31, 스펙 2026-07-31-account-score-fixed-denominator-design.md): 입력
-- raw의 집계 방식이 avg(분모=analyzed_count)에서 sum/고정분모(분모=analytics.recent-window)로
-- 바뀌면서(base CTE의 avg_hype_precise_raw 참조) raw 분포 자체가 이동해 앵커를 다시 적합했다
-- (1.4856/23.6566/56.3961/77.0479 → 1.2417/19.4383/52.2401/74.0179). 새 분포는 옛 분포보다
-- 살짝 낮게 이동한다 — 점수산출 콘텐츠가 창을 못 채운 계정들이 분모 고정으로 감점되면서 raw
-- 모집단 자체의 분위수가 내려간 것(계정 표본 하한 없음 결함 해소가 목적이므로 의도된 이동).
-- 2026-08-17 재적합(댓글 가중 1.5 기준 운영 코퍼스 실측, 스펙
-- docs/superpowers/specs/archive/2026-08-17-hype-comment-weight-design.md, 모수 n=4,583).
CREATE OR REPLACE FUNCTION analytics.hype_account_score_precise(raw numeric) RETURNS numeric
LANGUAGE sql STABLE AS $$
  WITH s AS (
    SELECT
      COALESCE(NULLIF((SELECT value::numeric FROM app_setting WHERE key='analytics.hype-anchor-acct-precise-p05'),0),1.3665)  AS a05,
      COALESCE(NULLIF((SELECT value::numeric FROM app_setting WHERE key='analytics.hype-anchor-acct-precise-p50'),0),26.6730) AS a50,
      COALESCE(NULLIF((SELECT value::numeric FROM app_setting WHERE key='analytics.hype-anchor-acct-precise-p90'),0),66.6060) AS a90,
      COALESCE(NULLIF((SELECT value::numeric FROM app_setting WHERE key='analytics.hype-anchor-acct-precise-p99'),0),85.2125) AS a99
  )
  SELECT CASE
    WHEN raw IS NULL THEN NULL
    ELSE GREATEST(LEAST(
      CASE
        WHEN raw <= s.a05 THEN 10*raw/NULLIF(s.a05,0)
        WHEN raw <= s.a50 THEN 10 + 35*(raw-s.a05)/NULLIF(s.a50-s.a05,0)
        WHEN raw <= s.a90 THEN 45 + 35*(raw-s.a50)/NULLIF(s.a90-s.a50,0)
        WHEN raw <= s.a99 THEN 80 + 17*(raw-s.a90)/NULLIF(s.a99-s.a90,0)
        ELSE 97 + 3*(raw-s.a99)/NULLIF(s.a99-s.a90,0)
      END, 100), 0)
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
-- avg_hype_raw: 위 반올림 전 평균 그 자체(정렬 전용, 스펙 §9 하위절 — 발굴 목록 상위권 동점 정렬 정합).
-- avg_hype_score와 같은 base 행에서 avg(...)를 한 번만 계산해 두 컬럼이 반드시 같은 값에서 파생되게 한다
-- (매핑 함수를 두 번 다른 식으로 부르면 표류할 수 있어 base CTE 안에서 값을 고정하고 밖에서 재사용).
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
         avg(analytics.hype_score(lower(content_type), views, likes, comments_count,
                                   followers,
                                   extract(epoch FROM (now() - uploaded_at)) / 86400.0))
                                                            AS avg_hype_raw,
         -- avg_hype_precise_raw (내부 전용, 노출 안 함): 위 avg_hype_raw와 입력은 같은 구조지만
         -- (hype_score_output(hype_score_raw(...)), 반올림 전, 02_serving.sql 신설), 집계는
         -- **단순 평균이 아니라 고정 분모 합**이다(2026-07-31 — 계정 표본 하한 없음 결함 해소,
         -- 스펙 2026-07-31-account-score-fixed-denominator-design.md). 이유: avg()는 분모가
         -- analyzed_count(창에 실제로 든 행 수)라, 창이 12로 꽉 찼어도 likes/comments 수집 누락으로
         -- 점수산출 콘텐츠가 1~2건뿐인 계정이 그 1~2건의 avg만으로 12건을 채운 계정과 동일하게
         -- 평가됐다(test 스택 실측: ynp.ny 2건 7위·sunyvvin 1건 8위·zero_lyrical 1건 12위 — 창이
         -- 꽉 찬 계정 6,350개 중 2,633개(41%)가 점수산출 <12건, 결손의 99.9%가 likes/comments NULL).
         -- 사용자 결정: 수집 누락이어도 감점한다 — 그 게시물은 인플루언서 상세 화면(최근 12개 카드)에
         -- 아예 뜨지 않아 유저 입장에서 "1개만 올린 계정"과 구분되지 않으므로 화면·점수 정합성이
         -- 우선이다. sum()은 NULL을 무시하므로 점수 불가 콘텐츠는 분자에 0 기여(=감점)가 되고,
         -- 분모는 창 크기(analyzed_count 아님)로 고정해 "표본이 적을수록 유리해지는" 구조를 없앤다.
         -- 분모는 01_recent_window.sql·04_analysis_candidates.sql과 동일하게 app_setting
         -- 'analytics.recent-window'(기본 12)를 읽는다 — 새 상수를 만들지 않는다. NULLIF(...,0)은
         -- 설정값이 실수로 0이 되어도 나눗셈 오류 없이 폴백 12로 떨어지게 하는 방어(이 파일의 다른
         -- 분모 NULLIF 관용구와 동일). 창 전체가 NULL이면 sum() 자체가 NULL이라 NULL/분모=NULL로
         -- "창 전체 점수 불가 → NULL" 기존 계약이 그대로 유지된다.
         sum(analytics.hype_score_output(
               analytics.hype_score_raw(lower(content_type), views, likes, comments_count,
                                         followers,
                                         extract(epoch FROM (now() - uploaded_at)) / 86400.0)))
           / NULLIF(COALESCE(
               (SELECT value::int FROM app_setting WHERE key = 'analytics.recent-window'), 12), 0)
                                                            AS avg_hype_precise_raw,
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
  analytics.hype_account_score(b.avg_hype_raw) AS avg_hype_score,
  b.views_sample_count,
  b.likes_sample_count,
  b.comments_sample_count,
  b.reels_count,
  b.feed_count,
  b.median_views,
  b.median_er_pct,
  b.top_views_share_pct,
  -- 스팬 일수(정수) — extract(day from interval)은 총 일수 성분을 그대로 준다(시:분초 절사).
  extract(day from (b.last_posted_at - b.first_posted_at))::int AS window_span_days,
  -- 이메일(스펙 2026-07-30-influencer-email-from-bio): bio 정규식 파싱, 첫 매치만·소문자 정규화.
  -- POSIX substring은 leftmost match만 반환하므로 "첫 번째만"이 자연히 성립. biography NULL이면 NULL.
  -- 운영 실측(37.5%, 오탐 0/30) 근거로 LLM 없이 정규식만 채택 — 뷰티 필터는 v_recent_content가 이미 적용.
  lower(substring(p.biography from '[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}')) AS email,
  -- avg_hype_raw는 맨 끝에 붙는다 — CREATE OR REPLACE VIEW는 기존 컬럼 사이에 새 컬럼을 끼워 넣지
  -- 못하고(DROP 없이는 위치 변경 불가) 끝에 추가하는 것만 허용한다. avg_hype_score·email도 같은
  -- 이유로 항상 그 시점의 맨 끝에 추가돼 왔다 — 이 컬럼도 그 선례를 따른다.
  b.avg_hype_raw,
  -- avg_hype_score_precise (2026-07-30, 스펙 §10) — 맨 끝에 추가(위와 동일 이유). avg_hype_precise_raw
  -- (콘텐츠 출력 매핑 반영 창 평균, base CTE)를 hype_account_score_precise()로 매핑한 소수값,
  -- round(...,4)로 자른다. avg_hype_score(bigint)·avg_hype_raw는 이 컬럼 추가로 값·의미가 바뀌지
  -- 않는다 — 완전히 독립된 새 함수·새 앵커가 새 raw 재료에 적용된다(위 hype_account_score_precise
  -- 주석 참조). was 발굴 목록 표시·정렬은 이제 이 컬럼을 쓴다.
  round(analytics.hype_account_score_precise(b.avg_hype_precise_raw), 4) AS avg_hype_score_precise
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
