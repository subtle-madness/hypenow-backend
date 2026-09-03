# 하입 스코어 댓글 가중 하향(×3→×1.5) 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

> 상태: ✅ 실행 완료(2026-08-17) · 스펙 [2026-08-17-hype-comment-weight-design.md](../../specs/archive/2026-08-17-hype-comment-weight-design.md) 승인됨

**Goal:** 하입 스코어 참여 항의 댓글 가중을 ×3에서 ×1.5로 낮추고 app_setting 키로 설정화하며, 4종 앵커를 신 가중 기준으로 재적합한다.

**Architecture:** 산식 변경 지점은 `analytics.hype_score_raw()`(02_serving.sql) 한 곳 — 계정 함수들은 raw 평균을 입력받는 매핑이라 가중과 무관하고 앵커 기본값 교체만 해당한다. 앵커 재적합 하니스(hype-anchor-refit)는 ①Q→②출력→③④계정 의존을 수동 다회 실행으로 풀던 구조 + 출력 앵커가 미러된 구 가중 정수 점수를 입력으로 쓰는 결함이 있어, 임시 테이블 체인의 단일 실행으로 개편한다. 운영 실행은 전부 읽기 전용(SELECT + TEMP TABLE).

**Tech Stack:** PostgreSQL(분석 뷰·함수), SQL 하니스(`analytics/test/run.sh`, BEGIN/ROLLBACK 격리), 운영 접근 `ssh hypenow` + `deploy-postgres-raw-1`.

**작업 위치:** worktree `.worktrees/hype-comment-weight`, 브랜치 `feat/hype-comment-weight` (스펙 커밋 094aa5c0 위에 쌓는다).

**전제:** 로컬 하니스는 실데이터가 필요 없다(더미 시드 + ROLLBACK). 로컬 `crawler-postgres-1` 컨테이너(포트 5433)가 떠 있으면 된다. `PG_CONTAINER`로 오버라이드 가능.

---

### Task 1: comment-weight 동등성 하니스 테스트 (선작성 — 실패 확인)

**Files:**
- Modify: `analytics/test/02_serving.test.sql` (기존 engage-weight 오버라이드 테스트 블록 뒤에 추가)

- [ ] **Step 1: 테스트 추가**

`02_serving.test.sql`에서 engage-weight 오버라이드를 검증하는 DO 블록(`'analytics.hype-engage-weight'` INSERT가 있는 블록)을 찾아 그 **뒤에** 아래 DO 블록을 추가한다. 기존 파일의 app_setting 조작 관용구(플레인 INSERT, 트랜잭션 롤백 의존)를 그대로 따른다:

```sql
-- comment-weight (2026-08-17, 댓글 가중 하향 스펙): 댓글 가중이 키로 설정화되고 기본 1.5인지.
-- 동등성으로 검증한다 — 가중 w의 정의상 hype_score_raw(t, v, likes, com, f, e)는
-- hype_score_raw(t, v, likes + w×com, 0, f, e)와 정확히 같아야 한다(참여 항 분자만 다름).
DO $$
DECLARE
  a numeric; b numeric;
BEGIN
  -- 기본 가중 1.5 — 피드 분기 (comments 40은 1.5배가 정수 60이 되도록 짝수 선택)
  a := analytics.hype_score_raw('feed', NULL, 100, 40, 5000, 0);
  b := analytics.hype_score_raw('feed', NULL, 160, 0, 5000, 0);
  ASSERT a = b, format('기본 댓글 가중 1.5(피드): %s != %s', a, b);

  -- 기본 가중 1.5 — 릴스 분기
  a := analytics.hype_score_raw('reels', 50000, 100, 40, 5000, 0);
  b := analytics.hype_score_raw('reels', 50000, 160, 0, 5000, 0);
  ASSERT a = b, format('기본 댓글 가중 1.5(릴스): %s != %s', a, b);

  -- 오버라이드 3 = 현행(×3) 산식 재현 — 스펙 §3 롤백 경로
  INSERT INTO app_setting(key, value) VALUES ('analytics.hype-comment-weight', '3');
  a := analytics.hype_score_raw('feed', NULL, 100, 40, 5000, 0);
  b := analytics.hype_score_raw('feed', NULL, 220, 0, 5000, 0);
  ASSERT a = b, format('댓글 가중 오버라이드 3(롤백 경로): %s != %s', a, b);
  DELETE FROM app_setting WHERE key = 'analytics.hype-comment-weight';
END $$;
```

- [ ] **Step 2: 실패 확인**

```bash
cd /Users/woomin/Project/hypenow-backend/.worktrees/hype-comment-weight/analytics && ./test/run.sh test/02_serving.test.sql
```

Expected: FAIL — 현행 함수는 ×3 고정이므로 "기본 댓글 가중 1.5(피드)" ASSERT에서 죽는다. (100+40×3=220 ≠ 160이라 a≠b.)

*이 시점에는 커밋하지 않는다 — Task 2와 함께 커밋.*

---

### Task 2: hype_score_raw에 comment-weight 키 추가

**Files:**
- Modify: `analytics/views/02_serving.sql:38-62` (s CTE·c CTE)
- Modify: `analytics/views/02_serving.sql:22` (헤더 주석의 키 목록)

- [ ] **Step 1: s CTE에 wc 상수 추가**

`hype_score_raw` 함수의 s CTE에서 `we` 줄(line 42) 바로 아래에 추가:

```sql
      COALESCE(NULLIF((SELECT value::numeric FROM app_setting WHERE key='analytics.hype-comment-weight'),0),1.5)      AS wc,
```

- [ ] **Step 2: c CTE의 참여 항 변경**

c CTE(line 56-64)의 두 분기에서 `comments*3`을 `comments*s.wc`로:

```sql
  c AS (
    SELECT s.*,
      (CASE WHEN content_type='reels'
        THEN s.wr * ln(1 + views::numeric/(COALESCE(followers,0)+1000))
           + s.we * ln(1 + ((likes + comments*s.wc)::numeric/(COALESCE(followers,0)+1000))/s.e0)
        ELSE ln(1 + ((likes + comments*s.wc)::numeric/(COALESCE(followers,0)+1000))/s.f0)
      END) AS q
    FROM s
  )
```

(`(likes + comments*s.wc)`는 wc가 numeric이라 결과도 numeric — 뒤의 `::numeric` 캐스트는 그대로 둬도 무해.)

- [ ] **Step 3: 헤더 주석 갱신**

line 22의 키 목록에 `hype-comment-weight(1.5: 2026-08-17 댓글 가중 하향 — 품앗이 댓글 패턴 과대평가 해소, 스펙 2026-08-17-hype-comment-weight-design.md)`를 추가하고, line 12·14의 산식 주석 `comments×3`을 `comments×wc`로 맞춘다.

- [ ] **Step 4: 테스트 통과 확인**

```bash
cd /Users/woomin/Project/hypenow-backend/.worktrees/hype-comment-weight/analytics && ./test/run.sh test/02_serving.test.sql
```

Expected: PASS (run.sh가 views/*.sql을 먼저 재적용하므로 새 함수가 반영된다).

- [ ] **Step 5: 나머지 02번 테스트 회귀 확인**

```bash
cd /Users/woomin/Project/hypenow-backend/.worktrees/hype-comment-weight/analytics && ./test/run.sh test/02_hype_reels_engage.test.sql && ./test/run.sh test/02_hype_v3_decay_order.test.sql && ./test/run.sh test/02_hype_output_mapping.test.sql
```

Expected: PASS 3건 — 이들은 단조성·비율·매핑만 검증하고 댓글 가중 절대값에 의존하지 않는다(앵커 기본값은 아직 안 바꿨다). 실패 시 여기서 멈추고 원인 파악.

- [ ] **Step 6: 커밋**

```bash
cd /Users/woomin/Project/hypenow-backend/.worktrees/hype-comment-weight && git add analytics/views/02_serving.sql analytics/test/02_serving.test.sql && git commit -m "feat(analytics): 하입 스코어 댓글 가중 설정화(×3→×1.5 기본) — analytics.hype-comment-weight

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 3: 앵커 재적합 하니스 개편 (단일 실행 체인)

**Files:**
- Rewrite: `analytics/check/hype-anchor-refit.sql`
- Modify: `analytics/check/hype-anchor-refit.sh:17-26`

구 구조의 두 문제를 함께 해소한다: ① 출력 앵커 섹션이 **미러된 정수 hype_score**(구 가중·구 앵커 산출물)를 모수로 써서 가중·Q 앵커 변경 시 낡은 분포를 적합하게 된다. ② ①Q→②출력→③④계정 앵커의 의존을 "산출값을 파일에 수동으로 박고 재실행"으로 풀어야 한다. 개편: analysis DB에서는 랭킹 경로 **short_code 목록만** 뽑고, crawler DB 세션 안에서 TEMP TABLE 체인으로 4종 앵커를 한 번에 산출한다.

- [ ] **Step 1: hype-anchor-refit.sh의 CSV 추출을 short_code로 변경**

line 18-23을 다음으로 교체 (`_ranking_hype_scores` → `_ranking_codes`):

```bash
  echo "CREATE TEMP TABLE _ranking_codes(short_code text);"
  echo "COPY _ranking_codes FROM STDIN WITH (FORMAT csv);"
  "${AQ[@]}" "COPY (SELECT c.short_code FROM contents c
                    JOIN content_analyses an ON an.short_code = c.short_code
                    WHERE an.is_beauty = true
                      AND (an.metric_timeliness = 'timely' OR an.metric_timeliness IS NULL)) TO STDOUT WITH (FORMAT csv)"
```

헤더 주석(line 6-9)도 갱신: "점수를 뽑아 주입"이 아니라 "랭킹 경로 소속 short_code만 뽑아 주입하고, 점수는 crawler DB에서 신 가중·신 Q 앵커 기준으로 재계산한다(v2, 2026-08-17)"로.

- [ ] **Step 2: hype-anchor-refit.sql 전면 재작성**

파일 전체를 아래로 교체한다. 산식 자체는 구 버전 섹션 ③④의 인라인 재현과 동일하고, 앵커 리터럴 대신 앞 단계 TEMP TABLE을 조인한다는 점만 다르다:

```sql
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
  COALESCE(NULLIF((SELECT value::numeric FROM app_setting WHERE key='analytics.hype-comment-weight'),0),1.5)     AS wc,
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
```

- [ ] **Step 3: 로컬 문법 검증**

로컬 컨테이너에는 랭킹 경로 데이터가 없어 값은 무의미하지만, 문법·체인 구조는 검증된다:

```bash
cd /Users/woomin/Project/hypenow-backend/.worktrees/hype-comment-weight/analytics && ./check/hype-anchor-refit.sh
```

Expected: 에러 없이 결과 4블록 출력(로컬은 n이 0 또는 극소 — 값은 버린다). `analytics.hype-comment-weight` 미설정 상태이므로 wc=1.5로 도는지 `_cfg` 정의를 눈으로 재확인.

- [ ] **Step 4: 커밋**

```bash
cd /Users/woomin/Project/hypenow-backend/.worktrees/hype-comment-weight && git add analytics/check/hype-anchor-refit.sql analytics/check/hype-anchor-refit.sh && git commit -m "feat(analytics): 앵커 재적합 하니스 v2 — 단일 체인 실행·출력 앵커 신선 재계산

구 버전은 출력 앵커를 미러된 구 가중 정수 점수에서 적합했고(가중 변경 시 낡은 분포),
Q→출력→계정 앵커 의존을 수동 다회 실행으로 풀어야 했다. short_code만 주입받아
TEMP TABLE 체인으로 4종을 한 번에 산출한다.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 4: 운영에서 앵커 재적합 실행 (읽기 전용)

**Files:** 없음 (측정만)

- [ ] **Step 1: 운영 실행**

refit.sh는 `docker exec`를 로컬에서 감싸므로 운영에서는 파일을 서버로 보내 실행한다:

```bash
cd /Users/woomin/Project/hypenow-backend/.worktrees/hype-comment-weight/analytics/check && ssh hypenow 'mkdir -p /tmp/refit' && scp hype-anchor-refit.sh hype-anchor-refit.sql hypenow:/tmp/refit/ && ssh hypenow 'cd /tmp/refit && chmod +x hype-anchor-refit.sh && PG_CONTAINER=deploy-postgres-raw-1 ./hype-anchor-refit.sh'
```

Expected: `① 콘텐츠 Q 앵커` 릴스·피드 2행(n 수만 단위), `② 출력 앵커` 1행(n 수천 단위 — 07-30 실측은 5,683), `③ 계정 앵커`·`④ 계정 소수점 앵커` 각 1행. 마지막에 `REFIT OK`.

주의: 전체 코퍼스 스캔이라 수 분 걸린다. 값이 07-30 기본값 대비 **전반적으로 낮아야** 정상(가중 하향으로 Q 분포가 내려가므로) — 예: 피드 p99 < 3.0144, 계정 소수 p99 < 74.0179. 높게 나오면 wc가 1.5로 안 읽힌 것이니 중단하고 원인 파악.

- [ ] **Step 2: 산출값 기록**

4블록의 p05/p50/p90/p99(+출력 anchor_max)를 다음 Task에서 쓰도록 기록해 둔다(스크래치패드 파일로 저장 권장). 운영 임시 파일 정리:

```bash
ssh hypenow 'rm -rf /tmp/refit'
```

---

### Task 5: 앵커 기본값 교체 (측정값 반영)

**Files:**
- Modify: `analytics/views/02_serving.sql` — `hype_score_raw()` s CTE의 q 앵커 8개 + `hype_score_output()` s CTE의 out 앵커 4개
- Modify: `analytics/views/10_account_detail.sql` — `hype_account_score()` 4개 + `hype_account_score_precise()` 4개

- [ ] **Step 1: 02_serving.sql q 앵커 교체**

`hype_score_raw`의 s CTE에서 8개 COALESCE 기본값(현행 릴스 0.1373/1.3798/4.5716/10.3883, 피드 0.0447/0.6135/1.6320/3.0144)을 Task 4 ①의 측정값으로 교체. 주변 주석(line 18-20)에 "2026-08-17 재적합(댓글 가중 1.5 기준, n=실측값)" 한 줄 추가.

- [ ] **Step 2: 02_serving.sql out 앵커 교체**

`hype_score_output`의 s CTE에서 4개 기본값(5/23/44/60.8)을 Task 4 ②의 측정값으로 교체. 함수 주석의 07-30 실측 서술 밑에 "2026-08-17 재적합(댓글 가중 1.5, 하니스 v2 — 미러 점수가 아닌 신 가중 재계산 분포 기준, n=실측값)" 추가.

- [ ] **Step 3: 10_account_detail.sql 계정 앵커 교체**

`hype_account_score`의 4개(1.0833/12.8333/31.2000/44.8600)를 Task 4 ③으로, `hype_account_score_precise`의 4개(1.2417/19.4383/52.2401/74.0179)를 Task 4 ④로 교체. 각 함수 주석에 재적합 사유 한 줄(스펙 링크 포함) 추가.

- [ ] **Step 4: 02_hype_output_mapping.test.sql 앵커점 기대값 갱신**

이 테스트는 앵커점 항등(`hype_score_output(5)=10, (23)=45, (44)=80, (60.8)=97`)을 기본값 리터럴로 검증한다 — 입력 4개를 Task 4 ②의 새 앵커값으로 교체한다(출력 10/45/80/97은 불변).

- [ ] **Step 5: 하니스 실행 — 앵커 민감 테스트 확인**

```bash
cd /Users/woomin/Project/hypenow-backend/.worktrees/hype-comment-weight/analytics && ./test/run.sh
```

Expected: 전부 PASS가 목표. **`10_account_score_rescale.test.sql`이 깨질 수 있다** — 동점 픽스처(dummy_alpha/dummy_zeta: 피드 likes 16400·17800·17100·17800, comments 0)는 댓글 가중과 무관(comments=0)하지만 **피드 Q 앵커·계정 앵커 교체**로 콘텐츠 점수(34/35/36)와 계정 매핑(85 동점)의 전제가 이동한다. 깨지면 Step 6 절차로 재교정하고, 안 깨지면 Step 6은 건너뛴다.

- [ ] **Step 6: (조건부) 10_account_score_rescale 픽스처 재교정**

테스트가 요구하는 성질은 "raw 평균이 근소하게 다른 두 계정이 정수 매핑에선 동점, avg_hype_raw 정렬로는 구분"이다. 새 앵커 기준으로 성질을 만족하는 likes 값을 하니스 DB에서 직접 역산한다:

```bash
docker exec crawler-postgres-1 psql -U crawler -d crawler -c "
SELECT l AS likes, analytics.hype_score('feed', NULL, l, 0, 100, 0) AS content_score
FROM generate_series(10000, 30000, 200) AS l;"
```

(함수 시그니처는 `hype_score(content_type, views, likes, comments, followers, elapsed_days)` — 위 예시의 `100` 자리(다섯 번째, followers)는 테스트 파일의 dummy_alpha 프로필 시드 followers 값으로 바꿔서 돌린다. 경과일 0은 픽스처가 `now() - interval '1~2 hours'`로 시드되므로 근사 일치.)

출력에서 "인접 content_score를 만드는 likes 쌍"을 골라 — 구 픽스처와 동일하게 (alpha: x·z), (zeta: y·z) 형태로 raw 평균이 0.5 차이 나되 정수 매핑이 같은 값을 고른다. 테스트 파일의 raw_media_page 시드 like_count 4개와 주석의 기대 점수를 갱신하고 재실행:

```bash
cd /Users/woomin/Project/hypenow-backend/.worktrees/hype-comment-weight/analytics && ./test/run.sh test/10_account_score_rescale.test.sql
```

Expected: PASS.

- [ ] **Step 7: 커밋**

```bash
cd /Users/woomin/Project/hypenow-backend/.worktrees/hype-comment-weight && git add analytics/views/02_serving.sql analytics/views/10_account_detail.sql analytics/test/02_hype_output_mapping.test.sql analytics/test/10_account_score_rescale.test.sql && git commit -m "feat(analytics): 하입 앵커 4종 재적합 — 댓글 가중 1.5 기준 운영 코퍼스 실측

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

(10_account_score_rescale를 안 고쳤으면 add 목록에서 제외.)

---

### Task 6: 운영 검증 시뮬레이션 (읽기 전용) — 스펙 §2 예측 부합 확인

**Files:** 없음 (측정만)

- [ ] **Step 1: 신 산식·신 앵커 기준 top-10 산출**

Task 3의 refit 하니스와 같은 방식으로, 이번엔 계정 소수 점수 상위 10을 뽑는 일회성 쿼리를 운영에서 돌린다. 아래를 스크래치패드에 `sim_final.sql`로 저장하고:

```sql
-- Task 5에서 확정한 새 기본값으로 <NEW_*> 8+4+4개를 치환해서 실행한다 (댓글 가중 1.5 인라인).
WITH r AS (
  SELECT owner_username, lower(content_type) AS typ, views, likes, comments_count AS com,
         (COALESCE(profile_followers,0)+1000)::numeric AS den,
         extract(epoch FROM (now()-uploaded_at))/86400.0 AS age_d
  FROM analytics.v_account_recent
),
sc AS (
  SELECT r.*,
         (likes IS NULL OR com IS NULL OR (typ='reels' AND views IS NULL)) AS bad,
         CASE WHEN typ='reels'
           THEN ln(1+views::numeric/den) + ln(1+((likes+com*1.5)/den)/0.01)
           ELSE ln(1+((likes+com*1.5)/den)/0.03) END AS q,
         CASE WHEN typ='reels' THEN <NEW_R05> ELSE <NEW_F05> END AS a05,
         CASE WHEN typ='reels' THEN <NEW_R50> ELSE <NEW_F50> END AS a50,
         CASE WHEN typ='reels' THEN <NEW_R90> ELSE <NEW_F90> END AS a90,
         CASE WHEN typ='reels' THEN <NEW_R99> ELSE <NEW_F99> END AS a99
  FROM r
),
m AS (
  SELECT owner_username, bad,
         GREATEST(LEAST(CASE
           WHEN q <= a05 THEN 10*q/a05
           WHEN q <= a50 THEN 10 + 35*(q-a05)/(a50-a05)
           WHEN q <= a90 THEN 45 + 35*(q-a50)/(a90-a50)
           WHEN q <= a99 THEN 80 + 17*(q-a90)/(a99-a90)
           ELSE 97 + 3*(q-a99)/(a99-a90) END, 100), 0)
         * power(0.5, GREATEST(age_d,0)/14) AS raw
  FROM sc
),
o AS (
  SELECT owner_username,
         CASE WHEN bad THEN NULL ELSE GREATEST(LEAST(CASE
           WHEN raw <= <NEW_O05> THEN 10*raw/<NEW_O05>
           WHEN raw <= <NEW_O50> THEN 10 + 35*(raw-<NEW_O05>)/(<NEW_O50>-<NEW_O05>)
           WHEN raw <= <NEW_O90> THEN 45 + 35*(raw-<NEW_O50>)/(<NEW_O90>-<NEW_O50>)
           WHEN raw <= <NEW_O99> THEN 80 + 17*(raw-<NEW_O90>)/(<NEW_O99>-<NEW_O90>)
           ELSE 97 + 3*(raw-<NEW_O99>)/(<NEW_O99>-<NEW_O90>) END, 100), 0) END AS outp
  FROM m
)
SELECT owner_username AS handle, round(sum(outp)/12, 2) AS acct_raw
FROM o GROUP BY 1 ORDER BY 2 DESC NULLS LAST LIMIT 10;
```

```bash
ssh hypenow "docker exec -i deploy-postgres-raw-1 psql -U crawler -d crawler -q" < <스크래치패드>/sim_final.sql
```

- [ ] **Step 2: 예측 부합 판정**

Expected: 1위가 실조회 기반 계정(설계 시점 시뮬레이션에서는 soft.lynaomi)이고 beauty_linyas2가 2위권. 설계 시뮬레이션(2026-08-17, 구 앵커 근사)과 크게 다르면 — 특히 beauty_linyas2가 여전히 1위면 — **중단하고 사용자에게 보고**(앵커 재적합이 순위에 미친 영향 분석 필요).

---

### Task 7: 문서 갱신·마무리

**Files:**
- Modify: `DECISIONS.md` (맨 위에 결정 추가)
- Modify: `docs/superpowers/specs/archive/2026-08-17-hype-comment-weight-design.md` (상태 헤더)
- Move: `docs/superpowers/plans/2026-08-17-hype-comment-weight.md` → `docs/superpowers/plans/archive/`

- [ ] **Step 1: DECISIONS.md 결정 추가**

기존 최상단 항목의 형식을 그대로 따라 맨 위에 추가:

```markdown
- **2026-08-17 하입 스코어 댓글 가중 ×3→×1.5 + 설정화** — 발굴 랭킹 1위 고착 진단: 참여 항
  `comments×3`이 댓글≈좋아요인 품앗이 패턴 계정을 과대평가(1위 계정 피드 분자의 ~78%가 댓글 몫).
  운영 시뮬레이션(w∈{3,2,1.5})에서 ×1.5만 실조회 기반 계정을 1위로 올려 채택. 가중은
  `analytics.hype-comment-weight`(기본 1.5, 함수 COALESCE 단일 소스)로 설정화 — 키에 3을 넣으면
  순위 응급 롤백(척도 완전 복원은 뷰 원복 필요). 앵커 4종 재적합 동반, refit 하니스는 단일 체인
  v2로 개편(미러 점수 대신 신 가중 재계산). [스펙](../../specs/archive/2026-08-17-hype-comment-weight-design.md)
```

- [ ] **Step 2: 스펙 상태 헤더 갱신**

`> 상태: 🟢 활성 · 설계 승인됨(2026-08-17) · 구현 전` → `> 상태: ✅ 구현됨(2026-08-17) — 배포는 develop→staging→main 승격으로`

- [ ] **Step 3: 플랜 아카이브 이동**

```bash
cd /Users/woomin/Project/hypenow-backend/.worktrees/hype-comment-weight && mkdir -p docs/superpowers/plans/archive && git mv docs/superpowers/plans/2026-08-17-hype-comment-weight.md docs/superpowers/plans/archive/ && grep -rn "plans/2026-08-17-hype-comment-weight" --exclude-dir=.git . || true
```

grep 히트가 있으면(스펙 등에서 옛 경로 참조) 링크를 `plans/archive/...`로 고친다. 플랜 파일 첫머리 상태 헤더도 `> 상태: ✅ 실행 완료(2026-08-17)`로 갱신.

- [ ] **Step 4: 최종 하니스 + 커밋 + push**

```bash
cd /Users/woomin/Project/hypenow-backend/.worktrees/hype-comment-weight/analytics && ./test/run.sh
```

Expected: 전부 PASS.

```bash
cd /Users/woomin/Project/hypenow-backend/.worktrees/hype-comment-weight && git add -A && git commit -m "docs: 댓글 가중 하향 결정 기록·플랜 아카이브

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>" && git push -u origin feat/hype-comment-weight
```

- [ ] **Step 5: 사용자 보고 (PR은 열지 않는다)**

push까지만 하고 결과(재적합 앵커값, Task 6 top-10, 하니스 결과)를 보고한 뒤 **PR 개설 여부를 사용자에게 묻는다** — PR은 명시 승인 후에만(전역 지침). CI의 `sql-harness` 잡이 PR에서 하니스를 다시 돌린다는 점, 머지·승격(develop→staging→main) 후 야간 미러 시점에 운영 랭킹이 바뀐다는 점을 함께 안내.

---

## 참고: 배포 후 확인 (머지·승격 뒤 별도 세션)

- staging(dev-api.hypenow.io) 배포 후 `GET /v1/influencers?sort=hype&limit=20`을 운영과 비교 — Task 6 예측과 부합하는지.
- 운영 승격 후 야간 미러(19:30 UTC) 뒤 같은 확인. 필요시 수동 미러 트리거: `ssh hypenow 'curl -s -X POST http://127.0.0.1:8082/ui/jobs/mirror'`.
- 응급 롤백: 운영 raw DB `app_setting`에 `analytics.hype-comment-weight=3` INSERT 후 미러 재실행(순위 응급 복원 — 척도는 신 앵커 기준이라 완전 복원 아님, 스펙 §3).
