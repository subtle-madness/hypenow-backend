-- hype_score 앵커 4종 재적합 후보값 산출 — v2 (2026-08-17, 댓글 가중 하향 스펙과 함께 개편).
-- 읽기 전용 — 실테이블에는 SELECT만 한다(TEMP TABLE은 세션 로컬). 대상 DB: crawler.
--
-- v2로 개편한 이유:
--   ① 구 버전은 출력 앵커를 미러된 정수 hype_score(analysis DB — 구 가중·구 앵커 산출물)에서
--      적합했다. 가중이나 Q 앵커를 바꾸는 재적합에서는 낡은 분포를 적합하게 되는 결함.
--      이제 analysis DB에서는 랭킹 경로 소속 short_code만 받아(_ranking_codes, refit.sh가 주입)
--      점수는 이 세션에서 신 상수 기준으로 재계산한다.
--   ② Q 앵커 → 출력 앵커 → 계정 앵커 의존을 수동 다회 실행으로 풀던 것을 TEMP TABLE 체인으로
--      한 번에 푼다. 앵커 리터럴을 파일에 박아두는 cs/cs2 CTE도 함께 사라졌다(표류 지점 제거).
--
-- 기준량·모수는 v1과 동일(v3 스펙 §4·§9·§10):
--   Q 앵커     — 감쇠 전 Q, 전체 서빙 코퍼스, 타입별
--   출력 앵커  — 랭킹 경로(is_beauty ∧ (timely OR NULL))의 정수 점수 분포, 타입 무관 단일
--   계정 앵커  — 창 콘텐츠 정수 점수 단순 평균(0점 및 raw<0.5 제외)
--   계정 소수  — 창 콘텐츠 출력 매핑 점수 합/고정 분모(recent-window), 동일 제외 규칙
--
-- 상수(halflife·e0·f0·wr·we·wc)는 app_setting 오버라이드가 있으면 그 값을 써야 하므로 함수와
-- 같은 COALESCE 관용구로 읽는다. 앵커는 여기서 산출하는 대상이라 읽지 않는다(전부 체인 산출).
--
-- 산출값 반영 절차:
--   1) hype-anchor-refit.sh 실행 → 결과 4블록(콘텐츠 Q 타입별 / 출력 / 계정 / 계정 소수)
--   2) 02_serving.sql: hype_score_raw()의 q-{reels,feed} 8개 + hype_score_output()의 out 4개
--      COALESCE 기본값 교체 (단일 소스는 함수 기본값 — app_setting은 런타임 오버라이드용)
--   3) 10_account_detail.sql: hype_account_score() 4개 + hype_account_score_precise() 4개 교체
--   4) 뷰 재적용 → 미러 잡 → 스팟체크 (deploy/README.md·런북)

-- 상수
CREATE TEMP TABLE _cfg AS
SELECT
  COALESCE(NULLIF((SELECT value::numeric FROM app_setting WHERE key='analytics.hype-fresh-halflife-days'),0),14) AS hl,
  COALESCE(NULLIF((SELECT value::numeric FROM app_setting WHERE key='analytics.hype-reels-e0'),0),0.01)          AS e0,
  COALESCE(NULLIF((SELECT value::numeric FROM app_setting WHERE key='analytics.hype-feed-f0'),0),0.03)           AS f0,
  COALESCE((SELECT value::numeric FROM app_setting WHERE key='analytics.hype-reach-weight'),1)                   AS wr,
  COALESCE((SELECT value::numeric FROM app_setting WHERE key='analytics.hype-engage-weight'),1)                  AS we,
  COALESCE((SELECT value::numeric FROM app_setting WHERE key='analytics.hype-comment-weight'),1.5)               AS wc,
  NULLIF(COALESCE((SELECT value::int FROM app_setting WHERE key='analytics.recent-window'),12),0)                AS win;

-- 전체 서빙 코퍼스의 Q (NULL 규칙은 함수와 동일)
CREATE TEMP TABLE _srcq AS
SELECT c.short_code, lower(c.content_type) AS content_type, c.uploaded_at,
  CASE WHEN lower(c.content_type)='reels'
    THEN g.wr * ln(1 + m.views::numeric/(COALESCE(pr.followers,0)+1000))
       + g.we * ln(1 + ((m.likes + m.comments_count*g.wc)::numeric/(COALESCE(pr.followers,0)+1000))/g.e0)
    ELSE ln(1 + ((m.likes + m.comments_count*g.wc)::numeric/(COALESCE(pr.followers,0)+1000))/g.f0)
  END AS q
FROM analytics.v_serving_content c
JOIN analytics.v_pinned_metrics m ON m.content_id = c.content_id
LEFT JOIN analytics.v_base_profile pr ON pr.username = c.owner_username
CROSS JOIN _cfg g
WHERE m.likes IS NOT NULL AND m.comments_count IS NOT NULL
  AND (lower(c.content_type) <> 'reels' OR m.views IS NOT NULL);

-- ① 콘텐츠 Q 앵커 (타입별)
CREATE TEMP TABLE _qa AS
SELECT content_type,
       count(*) AS n,
       percentile_cont(0.05) WITHIN GROUP (ORDER BY q) AS a05,
       percentile_cont(0.50) WITHIN GROUP (ORDER BY q) AS a50,
       percentile_cont(0.90) WITHIN GROUP (ORDER BY q) AS a90,
       percentile_cont(0.99) WITHIN GROUP (ORDER BY q) AS a99
FROM _srcq GROUP BY content_type;

SELECT '① 콘텐츠 Q 앵커' AS section, content_type, n,
       round(a05::numeric,4) AS anchor_p05, round(a50::numeric,4) AS anchor_p50,
       round(a90::numeric,4) AS anchor_p90, round(a99::numeric,4) AS anchor_p99
FROM _qa ORDER BY content_type;

-- 감쇠 전 매핑(base)·감쇠 후 raw — 서빙 코퍼스 전체에 대해 한 번만 계산해 뒤 단계가 공유
CREATE TEMP TABLE _scored AS
SELECT s.short_code, s.content_type,
  GREATEST(LEAST(CASE
    WHEN s.q <= a.a05 THEN 10*s.q/NULLIF(a.a05,0)
    WHEN s.q <= a.a50 THEN 10 + 35*(s.q-a.a05)/NULLIF(a.a50-a.a05,0)
    WHEN s.q <= a.a90 THEN 45 + 35*(s.q-a.a50)/NULLIF(a.a90-a.a50,0)
    WHEN s.q <= a.a99 THEN 80 + 17*(s.q-a.a90)/NULLIF(a.a99-a.a90,0)
    ELSE 97 + 3*(s.q-a.a99)/NULLIF(a.a99-a.a90,0) END, 100), 0)
  * power(0.5, GREATEST(extract(epoch FROM (now() - s.uploaded_at)) / 86400.0, 0)/g.hl) AS raw
FROM _srcq s
JOIN _qa a USING (content_type)
CROSS JOIN _cfg g;

-- ② 콘텐츠 출력 앵커 (랭킹 경로 모수, 정수 점수 분포 — v_contents.hype_score와 동일하게 round)
SELECT '② 출력 앵커(랭킹 경로)' AS section, count(*) AS n,
       round(percentile_cont(0.05) WITHIN GROUP (ORDER BY round(sc.raw))::numeric, 4) AS anchor_p05,
       round(percentile_cont(0.50) WITHIN GROUP (ORDER BY round(sc.raw))::numeric, 4) AS anchor_p50,
       round(percentile_cont(0.90) WITHIN GROUP (ORDER BY round(sc.raw))::numeric, 4) AS anchor_p90,
       round(percentile_cont(0.99) WITHIN GROUP (ORDER BY round(sc.raw))::numeric, 4) AS anchor_p99,
       max(round(sc.raw)) AS anchor_max
FROM _scored sc JOIN _ranking_codes rc USING (short_code);

-- 출력 앵커를 뒤 단계(계정 소수)가 조인으로 쓰도록 고정
CREATE TEMP TABLE _oa AS
SELECT percentile_cont(0.05) WITHIN GROUP (ORDER BY round(sc.raw)) AS o05,
       percentile_cont(0.50) WITHIN GROUP (ORDER BY round(sc.raw)) AS o50,
       percentile_cont(0.90) WITHIN GROUP (ORDER BY round(sc.raw)) AS o90,
       percentile_cont(0.99) WITHIN GROUP (ORDER BY round(sc.raw)) AS o99
FROM _scored sc JOIN _ranking_codes rc USING (short_code);

-- 계정 창 콘텐츠의 raw (v_account_recent 밑판 — NULL 규칙 행은 NULL 점수로 보존해 집계 규칙 유지)
CREATE TEMP TABLE _acct_scored AS
SELECT w.owner_username,
  CASE
    WHEN w.likes IS NULL OR w.comments_count IS NULL
      OR (lower(w.content_type)='reels' AND w.views IS NULL) THEN NULL
    ELSE
      GREATEST(LEAST(CASE
        WHEN qc.q <= a.a05 THEN 10*qc.q/NULLIF(a.a05,0)
        WHEN qc.q <= a.a50 THEN 10 + 35*(qc.q-a.a05)/NULLIF(a.a50-a.a05,0)
        WHEN qc.q <= a.a90 THEN 45 + 35*(qc.q-a.a50)/NULLIF(a.a90-a.a50,0)
        WHEN qc.q <= a.a99 THEN 80 + 17*(qc.q-a.a90)/NULLIF(a.a99-a.a90,0)
        ELSE 97 + 3*(qc.q-a.a99)/NULLIF(a.a99-a.a90,0) END, 100), 0)
      * power(0.5, GREATEST(extract(epoch FROM (now() - w.uploaded_at)) / 86400.0, 0)/g.hl)
  END AS raw
FROM analytics.v_account_recent w
CROSS JOIN _cfg g
JOIN _qa a ON a.content_type = lower(w.content_type)
CROSS JOIN LATERAL (
  SELECT CASE WHEN lower(w.content_type)='reels'
    THEN g.wr * ln(1 + w.views::numeric/(COALESCE(w.profile_followers,0)+1000))
       + g.we * ln(1 + ((w.likes + w.comments_count*g.wc)::numeric/(COALESCE(w.profile_followers,0)+1000))/g.e0)
    ELSE ln(1 + ((w.likes + w.comments_count*g.wc)::numeric/(COALESCE(w.profile_followers,0)+1000))/g.f0)
  END AS q
) qc;

-- ③ 계정 앵커 (hype_account_score 입력 기준량 — 정수 점수 단순 평균, 0점·raw<0.5 제외)
SELECT '③ 계정 앵커' AS section, count(*) AS n,
       round(percentile_cont(0.05) WITHIN GROUP (ORDER BY raw_avg)::numeric, 4) AS anchor_p05,
       round(percentile_cont(0.50) WITHIN GROUP (ORDER BY raw_avg)::numeric, 4) AS anchor_p50,
       round(percentile_cont(0.90) WITHIN GROUP (ORDER BY raw_avg)::numeric, 4) AS anchor_p90,
       round(percentile_cont(0.99) WITHIN GROUP (ORDER BY raw_avg)::numeric, 4) AS anchor_p99
FROM (SELECT owner_username, avg(round(raw)) AS raw_avg FROM _acct_scored GROUP BY 1) t
WHERE raw_avg >= 0.5;

-- ④ 계정 소수점 앵커 (hype_account_score_precise 입력 기준량 — 출력 매핑 점수 합/고정 분모)
SELECT '④ 계정 소수점 앵커' AS section, count(*) AS n,
       round(percentile_cont(0.05) WITHIN GROUP (ORDER BY raw_avg)::numeric, 4) AS anchor_p05,
       round(percentile_cont(0.50) WITHIN GROUP (ORDER BY raw_avg)::numeric, 4) AS anchor_p50,
       round(percentile_cont(0.90) WITHIN GROUP (ORDER BY raw_avg)::numeric, 4) AS anchor_p90,
       round(percentile_cont(0.99) WITHIN GROUP (ORDER BY raw_avg)::numeric, 4) AS anchor_p99
FROM (
  SELECT s.owner_username,
         sum(CASE WHEN s.raw IS NULL THEN NULL ELSE
           GREATEST(LEAST(CASE
             WHEN s.raw <= o.o05 THEN 10*s.raw/NULLIF(o.o05,0)
             WHEN s.raw <= o.o50 THEN 10 + 35*(s.raw-o.o05)/NULLIF(o.o50-o.o05,0)
             WHEN s.raw <= o.o90 THEN 45 + 35*(s.raw-o.o50)/NULLIF(o.o90-o.o50,0)
             WHEN s.raw <= o.o99 THEN 80 + 17*(s.raw-o.o90)/NULLIF(o.o99-o.o90,0)
             ELSE 97 + 3*(s.raw-o.o99)/NULLIF(o.o99-o.o90,0) END, 100), 0)
         END) / max(g.win) AS raw_avg
  FROM _acct_scored s CROSS JOIN _oa o CROSS JOIN _cfg g
  GROUP BY s.owner_username
) t
WHERE raw_avg >= 0.5;
