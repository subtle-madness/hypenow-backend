-- hype_score 앵커(analytics.hype-anchor-q-{reels,feed}-{p05,p50,p90,p99}) 재적합 후보값 산출.
-- 읽기 전용 — SELECT만 한다. 대상 DB: crawler(분석 뷰가 사는 곳).
--
-- 기준량은 감쇠 전 Q다 (v3~). v2.1까지는 감쇠 후 qf에 앵커를 맞췄는데, qf가 콘텐츠 연령에
-- 의존해서 앵커 캘리브레이션이 코퍼스 연령 구성에 오염됐다 — 피드는 릴스보다 옛날 꼬리가
-- 두꺼워 타입별로 다르게 눌렸고, 그게 피드 편향의 구조적 원인이었다.
-- (스펙 docs/superpowers/specs/2026-07-30-hype-score-v3-decay-after-mapping-design.md §2·§3)
--
-- 기준 모수는 전체 서빙 코퍼스 — v_serving_content 밑판 필터를 통과한 콘텐츠 전체 중
-- 함수의 NULL 규칙을 만족하는 집합. Q는 연령 무관이므로 timeliness·연령으로 좁히지 않는다(스펙 §4).
--
-- src CTE는 analytics/views/02_serving.sql의 v_contents가 실제로 쓰는 소스 뷰·조인과 동일하게
-- 맞춘다 — 함수에 넘어가는 것과 같은 지표를 읽어야 산출 앵커가 서빙과 어긋나지 않는다:
--   analytics.v_serving_content(밑판 필터) ⋈ analytics.v_pinned_metrics(핀 지표, content_id)
--   ⟕ analytics.v_base_profile(팔로워, username — v_contents와 동일하게 LEFT JOIN)
--
-- 산출값 반영 절차:
--   1) 이 스크립트를 돌려 타입별 p05/p50/p90/p99를 얻는다
--   2) analytics/views/02_serving.sql 의 함수 내 COALESCE 기본값을 그 값으로 교체
--      (기준값의 단일 소스는 함수 기본값이다 — app_setting은 재배포 없는 런타임 튜닝용 오버라이드)
--   3) 뷰 재적용 → 미러 잡 → 스팟체크 (deploy/README.md·런북)
--
-- 상수(halflife·e0·f0·wr·we)는 app_setting 오버라이드가 있으면 그 값을 써야 하므로 함수와 같은
-- COALESCE 관용구로 읽는다. 앵커 자체는 여기서 산출하는 대상이라 읽지 않는다.
WITH s AS (
  SELECT
    COALESCE(NULLIF((SELECT value::numeric FROM app_setting WHERE key='analytics.hype-reels-e0'),0),0.01) AS e0,
    COALESCE(NULLIF((SELECT value::numeric FROM app_setting WHERE key='analytics.hype-feed-f0'),0),0.03)  AS f0,
    COALESCE((SELECT value::numeric FROM app_setting WHERE key='analytics.hype-reach-weight'),1)          AS wr,
    COALESCE((SELECT value::numeric FROM app_setting WHERE key='analytics.hype-engage-weight'),1)         AS we
),
src AS (
  SELECT lower(c.content_type) AS content_type, m.views, m.likes, m.comments_count, pr.followers
  FROM analytics.v_serving_content c
  JOIN analytics.v_pinned_metrics m ON m.content_id = c.content_id
  LEFT JOIN analytics.v_base_profile pr ON pr.username = c.owner_username
),
q AS (
  SELECT src.content_type,
    CASE WHEN src.content_type='reels'
      THEN s.wr * ln(1 + src.views::numeric/(COALESCE(src.followers,0)+1000))
         + s.we * ln(1 + ((src.likes + src.comments_count*3)::numeric/(COALESCE(src.followers,0)+1000))/s.e0)
      ELSE ln(1 + ((src.likes + src.comments_count*3)::numeric/(COALESCE(src.followers,0)+1000))/s.f0)
    END AS value
  FROM src CROSS JOIN s
  -- 함수와 동일한 NULL 규칙: likes·comments NULL 제외, 릴스는 views NULL 제외
  WHERE src.likes IS NOT NULL AND src.comments_count IS NOT NULL
    AND (src.content_type <> 'reels' OR src.views IS NOT NULL)
)
SELECT
  content_type,
  count(*) AS n,
  round(percentile_cont(0.05) WITHIN GROUP (ORDER BY value)::numeric, 4) AS anchor_p05,
  round(percentile_cont(0.50) WITHIN GROUP (ORDER BY value)::numeric, 4) AS anchor_p50,
  round(percentile_cont(0.90) WITHIN GROUP (ORDER BY value)::numeric, 4) AS anchor_p90,
  round(percentile_cont(0.99) WITHIN GROUP (ORDER BY value)::numeric, 4) AS anchor_p99
FROM q
GROUP BY content_type
ORDER BY content_type;

-- =====================================================================================================
-- 계정 앵커(analytics.hype-anchor-acct-{p05,p50,p90,p99}) 재적합 후보값 산출 (트랙 Z 후속 — 계정 점수
-- 척도 재교정, 스펙 docs/superpowers/specs/2026-07-30-hype-score-v3-decay-after-mapping-design.md §9,
-- 구현 analytics/views/10_account_detail.sql의 analytics.hype_account_score()).
--
-- 주의(위 콘텐츠 앵커 산출과 다른 점): 이 시점 운영 DB는 아직 v2.1일 수 있다 — 이미 설치된
-- analytics.hype_score()를 그대로 호출하면 v2.1 기준(감쇠 후 qf에 앵커) 점수가 섞여 산출값이
-- 오염된다. 그래서 아래는 analytics.hype_score()를 호출하지 않고, 그 v3 산식(위 콘텐츠 앵커
-- 섹션과 동일한 Q·매핑·감쇠, 상수는 02_serving.sql의 COALESCE 기본값)을 **인라인으로 재현**한다 —
-- 롤아웃 전 운영 DB에서 돌려도 v3 계정 점수를 정확히 시뮬레이션해 값이 나온다.
-- (v_account_recent 자체는 hype_score를 호출하지 않는 밑판이라 버전 무관 — 10_account_detail.sql 주석 참조.)
--
-- 0점 제외 모수로 산출한다 — 전체 모수로 하면 무점수 콘텐츠뿐인 창(raw=0)이 다수라 p05가 0이
-- 되고, 매핑 함수 첫 구간(10×raw/a05)이 0/0=NULL이 되어버린다. "0에 반올림되는" raw<0.5도 함께
-- 제외해야 매핑 함수의 round 이후 산출값과 표본이 일치한다.
--
-- 산출값 반영 절차: 위 콘텐츠 앵커와 동일 — 1) 스크립트 실행 → 2) 10_account_detail.sql의
-- hype_account_score() 함수 내 COALESCE 기본값 교체 → 3) 뷰 재적용 → 미러 잡 → 스팟체크.
WITH cs AS (  -- 콘텐츠 함수 상수 — analytics.hype_score()의 s CTE와 동일 관용구(값은 02_serving.sql 기본값)
  SELECT
    COALESCE(NULLIF((SELECT value::numeric FROM app_setting WHERE key='analytics.hype-fresh-halflife-days'),0),14) AS hl,
    COALESCE(NULLIF((SELECT value::numeric FROM app_setting WHERE key='analytics.hype-reels-e0'),0),0.01)          AS e0,
    COALESCE(NULLIF((SELECT value::numeric FROM app_setting WHERE key='analytics.hype-feed-f0'),0),0.03)           AS f0,
    COALESCE((SELECT value::numeric FROM app_setting WHERE key='analytics.hype-reach-weight'),1)                   AS wr,
    COALESCE((SELECT value::numeric FROM app_setting WHERE key='analytics.hype-engage-weight'),1)                  AS we,
    COALESCE(NULLIF((SELECT value::numeric FROM app_setting WHERE key='analytics.hype-anchor-q-reels-p05'),0),0.1373)  AS r05,
    COALESCE(NULLIF((SELECT value::numeric FROM app_setting WHERE key='analytics.hype-anchor-q-reels-p50'),0),1.3798)  AS r50,
    COALESCE(NULLIF((SELECT value::numeric FROM app_setting WHERE key='analytics.hype-anchor-q-reels-p90'),0),4.5716)  AS r90,
    COALESCE(NULLIF((SELECT value::numeric FROM app_setting WHERE key='analytics.hype-anchor-q-reels-p99'),0),10.3883) AS r99,
    COALESCE(NULLIF((SELECT value::numeric FROM app_setting WHERE key='analytics.hype-anchor-q-feed-p05'),0),0.0447)   AS f05,
    COALESCE(NULLIF((SELECT value::numeric FROM app_setting WHERE key='analytics.hype-anchor-q-feed-p50'),0),0.6135)   AS f50,
    COALESCE(NULLIF((SELECT value::numeric FROM app_setting WHERE key='analytics.hype-anchor-q-feed-p90'),0),1.6320)   AS f90,
    COALESCE(NULLIF((SELECT value::numeric FROM app_setting WHERE key='analytics.hype-anchor-q-feed-p99'),0),3.0144)   AS f99
),
win AS (  -- 계정 최근창 밑판 — hype_score 버전과 무관(10_account_detail.sql v_account_recent와 동일 정의)
  SELECT owner_username, content_type, likes, comments_count, views, profile_followers AS followers, uploaded_at
  FROM analytics.v_account_recent
),
scored AS (
  SELECT w.owner_username, m.content_score
  FROM win w
  CROSS JOIN cs
  CROSS JOIN LATERAL (  -- Q (연령 무관)
    SELECT CASE WHEN lower(w.content_type)='reels'
             THEN cs.wr * ln(1 + w.views::numeric/(COALESCE(w.followers,0)+1000))
                + cs.we * ln(1 + ((w.likes + w.comments_count*3)::numeric/(COALESCE(w.followers,0)+1000))/cs.e0)
             ELSE ln(1 + ((w.likes + w.comments_count*3)::numeric/(COALESCE(w.followers,0)+1000))/cs.f0)
           END AS q
  ) qc
  CROSS JOIN LATERAL (  -- 매핑 후 감쇠 = analytics.hype_score()와 동일 산식, NULL 규칙도 동일
    SELECT CASE
      WHEN w.likes IS NULL OR w.comments_count IS NULL
        OR (lower(w.content_type)='reels' AND w.views IS NULL) THEN NULL
      ELSE round(
        GREATEST(LEAST(
          CASE WHEN lower(w.content_type)='reels' THEN
            CASE WHEN qc.q <= cs.r05 THEN 10*qc.q/NULLIF(cs.r05,0)
                 WHEN qc.q <= cs.r50 THEN 10 + 35*(qc.q-cs.r05)/NULLIF(cs.r50-cs.r05,0)
                 WHEN qc.q <= cs.r90 THEN 45 + 35*(qc.q-cs.r50)/NULLIF(cs.r90-cs.r50,0)
                 WHEN qc.q <= cs.r99 THEN 80 + 17*(qc.q-cs.r90)/NULLIF(cs.r99-cs.r90,0)
                 ELSE 97 + 3*(qc.q-cs.r99)/NULLIF(cs.r99-cs.r90,0) END
          ELSE
            CASE WHEN qc.q <= cs.f05 THEN 10*qc.q/NULLIF(cs.f05,0)
                 WHEN qc.q <= cs.f50 THEN 10 + 35*(qc.q-cs.f05)/NULLIF(cs.f50-cs.f05,0)
                 WHEN qc.q <= cs.f90 THEN 45 + 35*(qc.q-cs.f50)/NULLIF(cs.f90-cs.f50,0)
                 WHEN qc.q <= cs.f99 THEN 80 + 17*(qc.q-cs.f90)/NULLIF(cs.f99-cs.f90,0)
                 ELSE 97 + 3*(qc.q-cs.f99)/NULLIF(cs.f99-cs.f90,0) END
          END, 100), 0)
        * power(0.5, GREATEST(extract(epoch FROM (now() - w.uploaded_at)) / 86400.0, 0)/cs.hl)
      )
    END AS content_score
  ) m
),
raw AS (  -- 계정별 raw = 창 콘텐츠 점수 단순 평균(10_account_detail.sql base CTE와 동일 — 점수 불가는 자연 제외)
  SELECT owner_username, avg(content_score) AS raw_avg
  FROM scored
  GROUP BY owner_username
)
SELECT
  count(*) AS n,
  round(percentile_cont(0.05) WITHIN GROUP (ORDER BY raw_avg)::numeric, 4) AS anchor_p05,
  round(percentile_cont(0.50) WITHIN GROUP (ORDER BY raw_avg)::numeric, 4) AS anchor_p50,
  round(percentile_cont(0.90) WITHIN GROUP (ORDER BY raw_avg)::numeric, 4) AS anchor_p90,
  round(percentile_cont(0.99) WITHIN GROUP (ORDER BY raw_avg)::numeric, 4) AS anchor_p99
FROM raw
WHERE raw_avg >= 0.5;  -- 0점(및 반올림하면 0이 되는 0.5 미만) 제외
