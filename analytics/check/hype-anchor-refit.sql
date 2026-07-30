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
