# 릴스 hype_score 참여 축 팔로워 정규화 (v2.1) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 저조회수 릴스가 과도한 hype_score를 내는 문제를, 릴스 참여 축 분모를 `조회수`→`팔로워+1000`으로 바꿔(피드와 통일) 해소한다.

**Architecture:** 변경은 `analytics/views/02_serving.sql`의 `analytics.hype_score()` 함수 단 한 줄(릴스 engage 분모)이다. 조회수는 릴스에서 도달 축에만 남는다. 분모가 바뀌면 릴스 qf 분포가 이동하므로 릴스 앵커(p05/p50/p90/p99)를 운영 분포로 재적합한다. 화면 참여율 지표(`03_analysis_baseline`)와 피드 로직은 건드리지 않는다.

**Tech Stack:** PostgreSQL(analysis DB) SQL 뷰/함수, `app_setting` 런타임 튜닝, docker `crawler-postgres-1`(로컬, 포트 5433), 운영은 ssh `hypenow` + 미러 잡.

**참조 스펙:** `docs/superpowers/specs/2026-07-20-reels-hype-engage-follower-normalization-design.md`

> **실행 정정(2026-07-20):** 아래 psql 명령의 DB 이름 `analysis`는 **`crawler`가 맞음** — analytics 뷰·`analytics.hype_score`는 로컬/운영 모두 `crawler` DB에 정의(뷰가 raw를 읽음). 운영 컨테이너는 `deploy-postgres-raw-1`(analysis 아님). 재적합 실측값: e0=**0.01**, 릴스 앵커 **0.0736/0.7379/2.6312/5.5619**(운영 `v_analysis_candidates` 릴스 n=1717). 운영 `app_setting`에 hype 오버라이드 **없음** → 코드 기본값이 단일 소스라 Task4의 app_setting UPSERT 단계는 **생략**(뷰 재적용만).

---

## 사전 준비 (모든 psql 명령 공통)

로컬 DB 컨테이너 이름은 머신마다 다를 수 있다(CLAUDE.md 함정). 아래 러너를 사용:

```bash
PG_CONTAINER="${PG_CONTAINER:-crawler-postgres-1}"
run_sql() { docker exec -i "$PG_CONTAINER" psql -U crawler -d analysis -v ON_ERROR_STOP=1 "$@"; }
```

현재 로컬 `analysis` DB에는 v2 함수가 이미 로드돼 있다(뷰들이 적용된 상태). 없으면 먼저 `run_sql < analytics/views/02_serving.sql` 로 로드.

---

## Task 1: 회귀 테스트 작성 (함수 레벨, 시드 불필요)

`analytics.hype_score()`는 순수 함수라 뷰 시드 없이 리터럴 입력으로 직접 검증한다. 단언은 **매핑이 단조 증가**라는 성질에 기대므로 앵커/e0 값과 무관하게(재적합 후에도) 안정적이다.

**Files:**
- Create: `analytics/test/02_hype_reels_engage.test.sql`

- [ ] **Step 1: 테스트 파일 작성**

`analytics/test/02_hype_reels_engage.test.sql`:

```sql
-- 릴스 참여 축 팔로워 정규화(v2.1) 회귀 테스트.
-- 순수 함수 검증 — 쓰기 없음(트랜잭션 래핑 불필요). 단언은 매핑 단조성에 의존해 튜닝값과 무관.
DO $$
DECLARE
  c bigint; d bigint;   -- 조회수 단조성
  a bigint; b bigint;   -- 저조회수 뭉침 vs 도달
  n bigint;             -- NULL 규칙
BEGIN
  -- 1) 참여·팔로워 동일, 조회수만 다름 → 조회수 많은 쪽 점수가 낮으면 안 됨.
  --    (현재 ÷조회수 식은 조회수가 늘면 engage가 줄어 이 성질이 깨진다.)
  c := analytics.hype_score('reels', 1000,  500, 50, 5000, 0);
  d := analytics.hype_score('reels', 50000, 500, 50, 5000, 0);
  IF d < c THEN
    RAISE EXCEPTION '조회수 단조성 위반: views=1000 -> %, views=50000 -> %', c, d;
  END IF;

  -- 2) 저조회수 릴스가 잘 퍼진 릴스를 이기면 안 됨.
  a := analytics.hype_score('reels', 200,    300,  13,  10000, 0);  -- 저조회수 뭉침 케이스
  b := analytics.hype_score('reels', 100000, 3000, 200, 10000, 0);  -- 도달·참여 모두 큼
  IF a >= b THEN
    RAISE EXCEPTION '저조회수 뭉침: 200뷰 릴스(%)가 10만뷰 릴스(%)를 이김', a, b;
  END IF;

  -- 3) 릴스인데 조회수 NULL → 여전히 NULL(도달 축이 views를 쓰므로). 회귀 가드.
  n := analytics.hype_score('reels', NULL, 500, 50, 5000, 0);
  IF n IS NOT NULL THEN
    RAISE EXCEPTION '릴스 views NULL 규칙 위반: NULL이어야 하는데 %', n;
  END IF;

  RAISE NOTICE '02_hype_reels_engage: 모든 단언 통과';
END $$;
```

- [ ] **Step 2: 현재(미변경) 함수에 대해 실행 → FAIL 확인**

Run:
```bash
run_sql < analytics/test/02_hype_reels_engage.test.sql
```
Expected: `ERROR:  조회수 단조성 위반: views=1000 -> 100, views=50000 -> 93` (또는 단언 2 위반) 로 비정상 종료. **테스트가 현재 버그를 잡아냄을 확인.**

- [ ] **Step 3: 테스트 파일 커밋** (구현 전 실패 테스트를 먼저 남긴다)

```bash
git add analytics/test/02_hype_reels_engage.test.sql
git commit -m "test(analytics): 릴스 참여 축 팔로워 정규화 회귀 테스트(현재 FAIL)"
```

---

## Task 2: 릴스 engage 분모 교체 (핵심 변경)

**Files:**
- Modify: `analytics/views/02_serving.sql:47` (릴스 engage 식) + `:8` (주석)

- [ ] **Step 1: 함수 본문 한 줄 교체**

`analytics/views/02_serving.sql` 47번 줄:

```sql
           + s.we * ln(1 + ((likes + comments*3)::numeric/NULLIF(views,0))/s.e0)
```
을 아래로 교체(분모만 `NULLIF(views,0)` → `(COALESCE(followers,0)+1000)`):
```sql
           + s.we * ln(1 + ((likes + comments*3)::numeric/(COALESCE(followers,0)+1000))/s.e0)
```

- [ ] **Step 2: 주석 갱신** — 8번 줄:

```sql
--                    engage(릴스) = ln(1 + ((likes+comments×3)/views) / e0)
```
을 아래로 교체:
```sql
--                    engage(릴스) = ln(1 + ((likes+comments×3)/(followers+1000)) / e0)  -- v2.1(2026-07-20): 조회수→팔로워 정규화(저조회수 뭉침 해소). 조회수는 도달 축에만.
```

그리고 15번 줄의 `hype-reels-e0(0.02)` 옆 의미가 "뷰당"→"팔로워당"으로 바뀌었음을 같은 줄 끝 주석으로 남긴다:
```sql
--       ... hype-reels-e0(0.02: v2.1부터 팔로워당 참여 기준) ...
```
(15번 줄 서술을 해치지 않는 선에서 괄호만 보강)

- [ ] **Step 3: 변경한 함수를 로컬 DB에 로드**

Run:
```bash
run_sql < analytics/views/02_serving.sql
```
Expected: `CREATE FUNCTION` / `CREATE VIEW` … 에러 없이 완료.

- [ ] **Step 4: 회귀 테스트 재실행 → PASS 확인**

Run:
```bash
run_sql < analytics/test/02_hype_reels_engage.test.sql
```
Expected: `NOTICE:  02_hype_reels_engage: 모든 단언 통과`, 종료코드 0.

- [ ] **Step 5: 커밋**

```bash
git add analytics/views/02_serving.sql
git commit -m "feat(analytics): 릴스 hype_score 참여 축 조회수→팔로워 정규화(v2.1)

저조회수 릴스가 참여 비율 폭발로 과도한 점수를 내던 원인(engage 분모=조회수)을
피드와 동일하게 팔로워 정규화로 교체. 조회수는 릴스 도달 축에만 남는다."
```

---

## Task 3: 릴스 앵커 재적합 (운영 분포 기준)

분모 교체로 릴스 qf 분포가 이동하므로, 매핑 앵커를 운영 릴스 서빙 모수의 실제 qf 백분위로 다시 맞춘다. **e0는 0.02 유지** — 앵커가 실제 qf 분포의 백분위라 매핑을 온전히 재정의하므로 e0 재튜닝은 불필요(qf 축 라벨만 바꿀 뿐). 단, 아래 분포 확인에서 qf가 수치적으로 병적(전부 0 근처/거대)이면 그때 e0를 조정한다.

**Files:**
- Modify: `analytics/views/02_serving.sql:31,34,37,40` (릴스 앵커 기본값), `:13` (동결 날짜 주석)

- [ ] **Step 1: 운영에서 새 릴스 qf 백분위 산출 (읽기 전용)**

아래 쿼리를 운영 `analysis` DB에 실행. `v_contents`가 함수에 넘기는 것과 동일한 입력(뷰·핀 지표·프로필, now() 기준 경과일)으로 **변경 후 릴스 qf**를 재현해 백분위를 뽑는다.

```bash
cat <<'SQL' | ssh hypenow "docker exec -i postgres psql -U crawler -d analysis -v ON_ERROR_STOP=1"
WITH reels AS (
  SELECT p.views, p.likes, p.comments_count AS comments, pr.followers,
         extract(epoch FROM (now() - e.uploaded_at))/86400.0 AS ed
  FROM analytics.v_serving_content e
  JOIN analytics.v_pinned_metrics p USING (content_id)
  LEFT JOIN analytics.v_base_profile pr ON pr.username = e.owner_username
  WHERE lower(e.content_type) = 'reels'
    AND p.likes IS NOT NULL AND p.comments_count IS NOT NULL AND p.views IS NOT NULL
),
qf AS (
  SELECT ( ln(1 + views::numeric/(COALESCE(followers,0)+1000))
         + ln(1 + ((likes + comments*3)::numeric/(COALESCE(followers,0)+1000))/0.02) )
         * power(0.5, GREATEST(ed,0)/14) AS qf
  FROM reels
)
SELECT count(*) AS n,
       round(percentile_cont(0.05) WITHIN GROUP (ORDER BY qf)::numeric, 4) AS p05,
       round(percentile_cont(0.50) WITHIN GROUP (ORDER BY qf)::numeric, 4) AS p50,
       round(percentile_cont(0.90) WITHIN GROUP (ORDER BY qf)::numeric, 4) AS p90,
       round(percentile_cont(0.99) WITHIN GROUP (ORDER BY qf)::numeric, 4) AS p99
FROM qf;
SQL
```
Expected: `n`(모수 크기)과 `p05,p50,p90,p99` 4개 수치. (운영 DB 컨테이너/호스트는 메모리 런북 `analytics-prod-view-apply-mirror` 기준 — `ssh hypenow`, raw는 `postgres-raw`, 분석은 `postgres`. 실제 이름은 접속 후 `docker ps`로 확인.)

> qf가 전부 매우 작거나(예: p99<0.1) 거대하면(p05>5) e0를 조정해 p50이 대략 0.5~1.5 범위에 오도록 맞춘 뒤 이 쿼리를 다시 돌린다.

- [ ] **Step 2: 함수 기본 앵커를 산출값으로 갱신**

`analytics/views/02_serving.sql`에서 릴스 앵커 4개 기본값을 Step 1 결과로 교체(예시 — 실제 산출값으로 대체):

31번 줄 `...anchor-reels-p05'),0.2405)` → `...anchor-reels-p05'),<p05>)`
34번 줄 `...anchor-reels-p50'),0.9091)` → `...anchor-reels-p50'),<p50>)`
37번 줄 `...anchor-reels-p90'),1.8845)` → `...anchor-reels-p90'),<p90>)`
40번 줄 `...anchor-reels-p99'),2.9835)` → `...anchor-reels-p99'),<p99>)`

13번 줄 주석의 동결 근거를 갱신:
```sql
--   앵커값은 운영 분석 집합(2026-07-20 v2.1 재적합, 릴스는 팔로워 정규화 후 재산출) 산출·동결 — 모집단 이동 시 재보정(스펙 §6).
```

- [ ] **Step 3: 로컬 재로드 + 회귀 테스트 재확인**

Run:
```bash
run_sql < analytics/views/02_serving.sql
run_sql < analytics/test/02_hype_reels_engage.test.sql
```
Expected: 둘 다 에러 없이, 테스트는 `모든 단언 통과`. (앵커가 바뀌어도 단조성 단언은 유지된다.)

- [ ] **Step 4: 커밋**

```bash
git add analytics/views/02_serving.sql
git commit -m "feat(analytics): 릴스 hype 앵커 v2.1 재적합(팔로워 정규화 분포 기준)"
```

---

## Task 4: 운영 반영 + 문서 (사용자 명시 승인 필요)

> 운영 DB·미러를 건드리는 단계다. 실행 전 사용자에게 반영 시점 승인을 받는다. 절차는 메모리 런북 `analytics-prod-view-apply-mirror`를 따른다.

**Files:**
- Modify: `ARCHITECTURE.md` (§7 결정 기록, 필요 시 §5)

- [ ] **Step 1: PR 생성 (develop 대상)**

```bash
git push -u origin feat/reels-hype-engage-follower-norm
gh pr create --base develop --title "feat(analytics): 릴스 hype_score 참여 축 팔로워 정규화(v2.1)" \
  --body "저조회수 릴스 뭉침 해소 — 릴스 engage 분모 조회수→팔로워. 스펙/플랜 docs 포함. 화면 참여율 지표·피드 불변."
```

- [ ] **Step 2: (머지 후) 운영 뷰 적용** — 반드시 origin/develop 기준 워크트리에서(세션 간 되덮기 방지):

```bash
git -C .worktrees/reels-hype-engage fetch origin && git -C .worktrees/reels-hype-engage rebase origin/develop
cat .worktrees/reels-hype-engage/analytics/views/02_serving.sql \
  | ssh hypenow "docker exec -i postgres psql -U crawler -d analysis --single-transaction -v ON_ERROR_STOP=1"
```
Expected: 단일 트랜잭션으로 함수/뷰 재정의 완료.

- [ ] **Step 3: 운영 app_setting 앵커 오버라이드를 코드 기본값과 동기화**

(코드 기본값만으로도 동작하지만, 단일 소스 원칙상 운영 오버라이드가 남아있다면 새 값으로 맞춘다. 기존 오버라이드가 없으면 이 단계는 생략 가능 — 함수 COALESCE 기본값이 곧 새 값.)

```bash
ssh hypenow "docker exec -i postgres psql -U crawler -d analysis -v ON_ERROR_STOP=1" <<'SQL'
INSERT INTO app_setting(key,value) VALUES
 ('analytics.hype-anchor-reels-p05','<p05>'),
 ('analytics.hype-anchor-reels-p50','<p50>'),
 ('analytics.hype-anchor-reels-p90','<p90>'),
 ('analytics.hype-anchor-reels-p99','<p99>')
ON CONFLICT (key) DO UPDATE SET value=EXCLUDED.value;
SQL
```

- [ ] **Step 4: 미러 갱신 트리거** (락 컨보이 주의 — 실패 시 재시도):

```bash
ssh hypenow "curl -s -X POST http://127.0.0.1:8082/ui/jobs/mirror"
```

- [ ] **Step 5: 운영 스팟체크** — 릴스 점수 분포 + 이전 뭉침 케이스 하락 확인:

```bash
ssh hypenow "docker exec -i postgres psql -U crawler -d analysis" <<'SQL'
SELECT round(percentile_cont(0.5) WITHIN GROUP (ORDER BY hype_score)) AS median,
       count(*) FILTER (WHERE hype_score >= 91) AS top_band,
       count(*) AS n
FROM analytics.v_contents WHERE content_type='reels' AND hype_score IS NOT NULL;
-- 저조회수인데 상위였던 릴스가 내려왔는지: 조회수 하위 10% & 점수 상위 확인
SELECT short_code, views, likes, comments, hype_score
FROM analytics.v_contents
WHERE content_type='reels' AND hype_score IS NOT NULL
ORDER BY hype_score DESC LIMIT 20;
SQL
```
Expected: 중앙값 대략 45 부근, 상위 뭉침 개수 감소, 상위 20에 200뷰급 저조회수 릴스가 사라짐.

- [ ] **Step 6: ARCHITECTURE.md 결정 기록 갱신 + 커밋**

`ARCHITECTURE.md` §7에 한 줄 추가(예):
```
- 2026-07-20 hype_score v2.1: 릴스 참여 축 분모 조회수→팔로워(피처링식 정규화)로 저조회수 뭉침 해소. 조회수는 릴스 도달 축에만. 릴스 앵커 재적합. 화면 참여율 지표·피드 불변. (스펙/플랜 2026-07-20-reels-hype-engage-follower-normalization)
```
그리고 §5 작업 트랙 표에 해당 항목이 있으면 상태 갱신.

```bash
git add ARCHITECTURE.md && git commit -m "docs: ARCHITECTURE §7에 hype v2.1(릴스 팔로워 정규화) 결정 기록"
git push
```

- [ ] **Step 7: 계획 문서 아카이브 이동** (실행 완료 후):

```bash
git mv docs/superpowers/plans/2026-07-20-reels-hype-engage-follower-normalization.md docs/superpowers/plans/archive/
git commit -m "docs: 릴스 팔로워 정규화 플랜 아카이브 이동"
```

---

## Self-Review 메모

- **스펙 커버리지**: 분모 교체(Task 2) / 앵커 재적합·e0 판단(Task 3) / 화면 참여율·피드 불변(변경 안 함으로 커버) / SQL 하니스·운영 스팟체크(Task 1·4) / origin-develop 되덮기 주의(Task 4 Step 2) — 스펙 §전 항목 대응.
- **타입 일관성**: 함수 시그니처 6-인자 불변, 앵커 키 이름 `analytics.hype-anchor-reels-*` 일관.
- **플레이스홀더**: `<p05>` 등은 미상값이 아니라 Task 3 Step 1 쿼리 산출값을 넣는 자리 — 실행 시 확정. 코드/명령은 모두 실제 내용 포함.
