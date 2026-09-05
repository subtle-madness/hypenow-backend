-- 09-04 수집 회귀 감시 트랙(feat/grafana-collection-alerts) 백테스트 SQL
-- 목적: rules.yaml의 회귀 룰이 2026-08-16~09-05(KST) 구간에서 언제 fire했을지 재현.
-- 전부 순수 SELECT — 운영 DB에 그대로 붙여도 안전(쓰기 없음).
--
-- 실행 관용구(읽기 전용):
--   ssh -o BatchMode=yes hypenow 'docker exec -i deploy-postgres-raw-1 psql -U crawler -d crawler -tA' \
--     < deploy/grafana/backtest/2026-09-04-collection-regression.sql   -- crawler DB 섹션만
--   ssh -o BatchMode=yes hypenow 'docker exec -i deploy-postgres-1 psql -U monitoring -d monitoring -tA' \
--     < deploy/grafana/backtest/2026-09-04-collection-regression.sql   -- monitoring DB 섹션만
-- (crawler·monitoring DB가 서로 다른 postgres 클러스터라 한 파일을 통째로 두 세션에 나눠 붙인다.
--  섹션 구분은 아래 "-- === crawler DB ===" / "-- === monitoring DB ===" 참고.)
--
-- 결과 요약과 판단 근거는 같은 디렉토리의 2026-09-04-collection-regression.md 참조.

-- === crawler DB (psql -U crawler -d crawler) ===

-- [1] raw_profile 일별 건수 + 직전 7일(어제 제외) 중앙값 — collection-raw-profile-daily 재현
-- SOFT=1500 HARD=500: fire 조건은 (v < median*0.5 AND v < 1500) OR v < 500
WITH days AS (
  SELECT generate_series((current_date - interval '20 days')::date, (current_date - interval '1 day')::date, interval '1 day')::date AS d
),
daily AS (
  SELECT (captured_at AT TIME ZONE 'Asia/Seoul')::date AS d, count(*) AS v
  FROM raw_profile
  WHERE captured_at > now() - interval '28 days'
  GROUP BY 1
),
filled AS (
  SELECT days.d, COALESCE(daily.v,0)::numeric AS v FROM days LEFT JOIN daily ON daily.d = days.d
)
SELECT f.d AS 날짜, f.v AS 어제값,
  (SELECT percentile_cont(0.5) WITHIN GROUP (ORDER BY f2.v)
   FROM filled f2 WHERE f2.d BETWEEN f.d - 7 AND f.d - 1) AS 중앙값7일,
  CASE WHEN (f.v < (SELECT percentile_cont(0.5) WITHIN GROUP (ORDER BY f2.v) FROM filled f2 WHERE f2.d BETWEEN f.d - 7 AND f.d - 1) * 0.5 AND f.v < 1500) OR f.v < 500
       THEN 'FIRE' ELSE 'ok' END AS 판정
FROM filled f
ORDER BY f.d;

-- [2] crawl_run REELS 성공 런수 — collection-reels-runs-daily 재현 (SOFT=1500 HARD=800)
WITH days AS (
  SELECT generate_series((current_date - interval '20 days')::date, (current_date - interval '1 day')::date, interval '1 day')::date AS d
),
daily AS (
  SELECT (started_at AT TIME ZONE 'Asia/Seoul')::date AS d, count(*) AS v
  FROM crawl_run
  WHERE started_at > now() - interval '28 days' AND job = 'REELS' AND status = 'SUCCEEDED'
  GROUP BY 1
),
filled AS (
  SELECT days.d, COALESCE(daily.v,0)::numeric AS v FROM days LEFT JOIN daily ON daily.d = days.d
)
SELECT f.d AS 날짜, f.v AS 어제값,
  (SELECT percentile_cont(0.5) WITHIN GROUP (ORDER BY f2.v)
   FROM filled f2 WHERE f2.d BETWEEN f.d - 7 AND f.d - 1) AS 중앙값7일,
  CASE WHEN (f.v < (SELECT percentile_cont(0.5) WITHIN GROUP (ORDER BY f2.v) FROM filled f2 WHERE f2.d BETWEEN f.d - 7 AND f.d - 1) * 0.5 AND f.v < 1500) OR f.v < 800
       THEN 'FIRE' ELSE 'ok' END AS 판정
FROM filled f
ORDER BY f.d;

-- [3] crawl_run COLLECT 성공 런수 — collection-collect-runs-daily 재현 (SOFT=1500 HARD=800)
WITH days AS (
  SELECT generate_series((current_date - interval '20 days')::date, (current_date - interval '1 day')::date, interval '1 day')::date AS d
),
daily AS (
  SELECT (started_at AT TIME ZONE 'Asia/Seoul')::date AS d, count(*) AS v
  FROM crawl_run
  WHERE started_at > now() - interval '28 days' AND job = 'COLLECT' AND status = 'SUCCEEDED'
  GROUP BY 1
),
filled AS (
  SELECT days.d, COALESCE(daily.v,0)::numeric AS v FROM days LEFT JOIN daily ON daily.d = days.d
)
SELECT f.d AS 날짜, f.v AS 어제값,
  (SELECT percentile_cont(0.5) WITHIN GROUP (ORDER BY f2.v)
   FROM filled f2 WHERE f2.d BETWEEN f.d - 7 AND f.d - 1) AS 중앙값7일,
  CASE WHEN (f.v < (SELECT percentile_cont(0.5) WITHIN GROUP (ORDER BY f2.v) FROM filled f2 WHERE f2.d BETWEEN f.d - 7 AND f.d - 1) * 0.5 AND f.v < 1500) OR f.v < 800
       THEN 'FIRE' ELSE 'ok' END AS 판정
FROM filled f
ORDER BY f.d;

-- [부가] raw_profile source별 분해(사고 시 어떤 소스가 꺼졌는지 확인용, KST)
SELECT (captured_at AT TIME ZONE 'Asia/Seoul')::date AS 날짜,
       to_char(captured_at AT TIME ZONE 'Asia/Seoul', 'Dy') AS 요일,
       source, count(*)
FROM raw_profile
WHERE captured_at > now() - interval '20 days'
GROUP BY 1,2,3 ORDER BY 1,3;


-- === monitoring DB (psql -U monitoring -d monitoring) ===

-- [4] raw.fetch_payload(Hiker 콜) 일 합계 — collection-hiker-calls-daily 재현 (SOFT=1000 HARD=500)
WITH days AS (
  SELECT generate_series((current_date - interval '20 days')::date, (current_date - interval '1 day')::date, interval '1 day')::date AS d
),
daily AS (
  SELECT (fetched_at AT TIME ZONE 'Asia/Seoul')::date AS d, count(*) AS v
  FROM raw.fetch_payload
  WHERE fetched_at > now() - interval '28 days'
  GROUP BY 1
),
filled AS (
  SELECT days.d, COALESCE(daily.v,0)::numeric AS v FROM days LEFT JOIN daily ON daily.d = days.d
)
SELECT f.d AS 날짜, f.v AS 어제값,
  (SELECT percentile_cont(0.5) WITHIN GROUP (ORDER BY f2.v)
   FROM filled f2 WHERE f2.d BETWEEN f.d - 7 AND f.d - 1) AS 중앙값7일,
  CASE WHEN (f.v < (SELECT percentile_cont(0.5) WITHIN GROUP (ORDER BY f2.v) FROM filled f2 WHERE f2.d BETWEEN f.d - 7 AND f.d - 1) * 0.5 AND f.v < 1000) OR f.v < 500
       THEN 'FIRE' ELSE 'ok' END AS 판정
FROM filled f
ORDER BY f.d;

-- [5] brand_tagged_post first_seen_at(태그 발견) 일별 건수 — collection-brand-tagged-discovery-daily
-- 재현 (SOFT=150 HARD=50)
WITH days AS (
  SELECT generate_series((current_date - interval '20 days')::date, (current_date - interval '1 day')::date, interval '1 day')::date AS d
),
daily AS (
  SELECT (first_seen_at AT TIME ZONE 'Asia/Seoul')::date AS d, count(*) AS v
  FROM brand_tagged_post
  WHERE first_seen_at > now() - interval '28 days'
  GROUP BY 1
),
filled AS (
  SELECT days.d, COALESCE(daily.v,0)::numeric AS v FROM days LEFT JOIN daily ON daily.d = days.d
)
SELECT f.d AS 날짜, f.v AS 어제값,
  (SELECT percentile_cont(0.5) WITHIN GROUP (ORDER BY f2.v)
   FROM filled f2 WHERE f2.d BETWEEN f.d - 7 AND f.d - 1) AS 중앙값7일,
  CASE WHEN (f.v < (SELECT percentile_cont(0.5) WITHIN GROUP (ORDER BY f2.v) FROM filled f2 WHERE f2.d BETWEEN f.d - 7 AND f.d - 1) * 0.5 AND f.v < 150) OR f.v < 50
       THEN 'FIRE' ELSE 'ok' END AS 판정
FROM filled f
ORDER BY f.d;

-- [6] brand_tagged_post enriched_at(태그 보강) 일별 건수 — collection-brand-tagged-enrich-daily
-- 재현 (SOFT=30 HARD=10)
WITH days AS (
  SELECT generate_series((current_date - interval '20 days')::date, (current_date - interval '1 day')::date, interval '1 day')::date AS d
),
daily AS (
  SELECT (enriched_at AT TIME ZONE 'Asia/Seoul')::date AS d, count(*) AS v
  FROM brand_tagged_post
  WHERE enriched_at > now() - interval '28 days'
  GROUP BY 1
),
filled AS (
  SELECT days.d, COALESCE(daily.v,0)::numeric AS v FROM days LEFT JOIN daily ON daily.d = days.d
)
SELECT f.d AS 날짜, f.v AS 어제값,
  (SELECT percentile_cont(0.5) WITHIN GROUP (ORDER BY f2.v)
   FROM filled f2 WHERE f2.d BETWEEN f.d - 7 AND f.d - 1) AS 중앙값7일,
  CASE WHEN (f.v < (SELECT percentile_cont(0.5) WITHIN GROUP (ORDER BY f2.v) FROM filled f2 WHERE f2.d BETWEEN f.d - 7 AND f.d - 1) * 0.5 AND f.v < 30) OR f.v < 10
       THEN 'FIRE' ELSE 'ok' END AS 판정
FROM filled f
ORDER BY f.d;

-- [9] brand_post_snapshot 좋아요 NULL 비율(likes_hidden 제외) — quality-brand-post-likes-null-daily
-- 재현. 원 스펙 절대 하한 0.3은 백테스트 결과 실측 최대치(0.059)를 훨씬 웃돌아 죽은 임계값이라
-- 0.02로 낮췄다(rules.yaml 주석과 동기화됨).
WITH days AS (
  SELECT generate_series((current_date - interval '20 days')::date, (current_date - interval '1 day')::date, interval '1 day')::date AS d
),
daily AS (
  SELECT captured_on AS d, avg((likes IS NULL AND NOT likes_hidden)::int)::numeric AS v
  FROM brand_post_snapshot
  WHERE captured_on > current_date - interval '28 days'
  GROUP BY 1
),
filled AS (
  SELECT days.d, COALESCE(daily.v,0)::numeric AS v FROM days LEFT JOIN daily ON daily.d = days.d
)
SELECT f.d AS 날짜, round(f.v,4) AS 어제비율,
  round((SELECT percentile_cont(0.5) WITHIN GROUP (ORDER BY f2.v)
   FROM filled f2 WHERE f2.d BETWEEN f.d - 7 AND f.d - 1)::numeric,4) AS 중앙값7일,
  CASE WHEN f.v > (SELECT percentile_cont(0.5) WITHIN GROUP (ORDER BY f2.v) FROM filled f2 WHERE f2.d BETWEEN f.d - 7 AND f.d - 1) * 2
            AND f.v > 0.02
       THEN 'FIRE' ELSE 'ok' END AS 판정
FROM filled f
ORDER BY f.d;

-- [b] sweep_run 상태 확인 — collection-sweep-run-missing이 실제 룰과 동일하게 쓰는 쿼리.
-- 백테스트: 최근 10일 전부 ok=t라 이 룰이 fire한 적은 없음(구조 검증만, 값은 실행 시점마다 바뀜).
SELECT CASE
  WHEN max(started_at) < now() - interval '26 hours' THEN 1
  WHEN (SELECT ok FROM sweep_run ORDER BY started_at DESC LIMIT 1) IS FALSE THEN 2
  ELSE 0
END AS value
FROM sweep_run;

-- [부가] sweep_run 최근 10행(정상성 육안 확인용)
SELECT started_at, completed_at, ok FROM sweep_run ORDER BY started_at DESC LIMIT 10;
