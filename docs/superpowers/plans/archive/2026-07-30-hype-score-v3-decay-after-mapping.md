# 하입 스코어 v3 (감쇠를 매핑 뒤로) 구현 계획

> 상태: ✅ 구현/실행/반영됨 (2026-07-30 운영 배포 · 후속 §9~§11 포함, 트랙 Z)
> 스펙: `docs/superpowers/specs/2026-07-30-hype-score-v3-decay-after-mapping-design.md`

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `analytics.hype_score()`의 감쇠 적용 시점을 앵커 매핑 뒤로 옮겨, 앵커 캘리브레이션이 코퍼스 연령 구성에 오염되지 않게 하고 피드/릴스 타입 편향을 해소한다.

**Architecture:** 산식은 SQL 함수 하나(`analytics/views/02_serving.sql`)가 정본이고 Java에는 계산 로직이 없다. 함수 본문에서 `qf = Q × decay` 후 매핑하던 순서를 `map_Q(Q)` → `[0,100]` 클램프 → `× decay` → `round`로 바꾸고, 앵커 8개 기본값을 `Q` 기준으로 교체한다. app_setting 앵커 키는 기준량이 바뀌었으므로 `hype-anchor-q-*`로 개명한다. 검증은 SQL 하니스(더미 시드 + BEGIN/ROLLBACK)로 성질 기반 단언을 추가한다.

**Tech Stack:** PostgreSQL(SQL 함수·뷰), bash 하니스(`analytics/test/run.sh`), Gradle 멀티모듈(Java 21 / Spring Boot 4.1) — 이번 변경에 Java 코드 수정은 주석 갱신 1건뿐.

---

## 작업 환경

- 워크트리: `/Users/woomin/Project/hypenow-backend/.worktrees/hype-v3`, 브랜치 `feat/hype-score-v3-decay-after-mapping` (origin/develop 기준). 메인 체크아웃은 세션 공유라 건드리지 않는다.
- 로컬 하니스는 실데이터 postgres 컨테이너가 필요하다. 기본 `crawler-postgres-1`, 머신에 따라 이름이 다르면 `PG_CONTAINER`로 오버라이드. Docker는 colima가 정본이라 `DOCKER_HOST`가 필요할 수 있다.
- 운영 조회는 읽기 전용만. analysis DB = `deploy-postgres-1`(`celfit`/`analysis`), 분석 뷰·`app_setting` = `deploy-postgres-raw-1`(`crawler`/`crawler`), 접속은 `ssh hypenow`.

## 파일 구조

| 파일 | 책임 | 작업 |
|---|---|---|
| `analytics/views/02_serving.sql` | 하입 스코어 산식 정본(함수) + 서빙 뷰 | 수정 (함수 본문·앵커 기본값·app_setting 키·헤더 주석) |
| `analytics/test/02_hype_v3_decay_order.test.sql` | v3 감쇠 순서·타입 동등성 회귀 가드 | 신설 |
| `analytics/test/02_serving.test.sql` | 기존 함수·뷰 단언 | 수정 (앵커 키 이름, v2→v3 주석) |
| `analytics/check/hype-anchor-refit.sql` | 앵커 재적합 후보값 산출(읽기 전용) | 신설 |
| `analytics/check/hype-anchor-refit.sh` | 위 쿼리 실행 래퍼 | 신설 |
| `contract-analysis/src/main/java/com/celfit/contract/analysis/Content.java` | 분석 결과 계약 record | 수정 (stale한 v1 Javadoc 갱신) |
| `contract-analysis/src/main/java/com/celfit/contract/analysis/ContentMetricSnapshot.java` | 스냅샷 계약 record | 수정 (동일) |
| `ARCHITECTURE.md` | 살아있는 구조·트랙 문서 | 수정 (§5 트랙 추가, §7 결정 기록) |

---

### Task 1: 앵커 재적합 재현 쿼리 신설

스펙 §5-2. 이번 사고의 근본 원인 중 하나가 "v2.1 스펙에 앵커 재현 절차가 없어 피드 앵커 누락을 검증할 수 없었다"는 점이므로, 산출 쿼리를 저장소에 남긴다. 읽기 전용이라 테스트가 없다 — 대신 실행이 성공하고 두 타입 4점이 나오는 것으로 검증한다.

**Files:**
- Create: `analytics/check/hype-anchor-refit.sql`
- Create: `analytics/check/hype-anchor-refit.sh`

- [ ] **Step 1: 산출 쿼리 작성**

`analytics/check/hype-anchor-refit.sql`:

```sql
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
  JOIN analytics.v_content_pinned_metrics m ON m.content_id = c.content_id
  JOIN analytics.v_account_profile_pinned pr ON pr.owner_username = c.owner_username
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
```

**주의:** `src` CTE의 조인 대상 뷰 이름(`v_content_pinned_metrics`·`v_account_profile_pinned`)은 실제
`02_serving.sql`의 `v_contents` 정의에서 쓰는 이름으로 맞춰야 한다. Step 2에서 확인해 수정한다.

- [ ] **Step 2: `v_contents`가 실제로 쓰는 소스 뷰·컬럼 이름 확인 후 쿼리 수정**

Run:
```bash
sed -n '95,120p' analytics/views/02_serving.sql
```

`v_contents`의 `FROM`/`JOIN` 절과 `analytics.hype_score(...)` 호출 인자에 쓰인 실제 뷰·컬럼 이름을
확인하고, Step 1의 `src` CTE를 그 이름으로 교체한다. 목표는 "함수에 넘어가는 것과 **같은** 지표"를
읽는 것이다 — 다른 소스를 읽으면 산출 앵커가 서빙과 어긋난다.

- [ ] **Step 3: 실행 래퍼 작성**

`analytics/check/hype-anchor-refit.sh`:

```bash
#!/usr/bin/env bash
# hype_score 앵커 재적합 후보값을 산출한다(읽기 전용).
# 사용법: ./check/hype-anchor-refit.sh   (실데이터 postgres 컨테이너 필요 — 이름이 다르면 PG_CONTAINER로 지정)
# 대상은 crawler DB(분석 뷰가 사는 곳) — coverage.sh(analysis DB)와 대상이 다르다.
set -euo pipefail
cd "$(dirname "$0")"

# 컨테이너 이름은 compose 디렉토리명 기반이라 머신마다 다르다 — PG_CONTAINER로 오버라이드
docker exec -i "${PG_CONTAINER:-crawler-postgres-1}" psql -U crawler -d crawler -v ON_ERROR_STOP=1 < hype-anchor-refit.sql
echo "REFIT OK (산출값 반영 절차는 hype-anchor-refit.sql 헤더 주석 참조)"
```

- [ ] **Step 4: 실행 권한 부여 후 로컬 실행**

Run:
```bash
chmod +x analytics/check/hype-anchor-refit.sh && (cd analytics && ./check/hype-anchor-refit.sh)
```

Expected: `content_type | n | anchor_p05 | anchor_p50 | anchor_p90 | anchor_p99` 2행(feed·reels) + `REFIT OK`.

로컬 DB에 실데이터가 없어 행이 0건이거나 컨테이너가 없어 실패하면 여기서 멈추지 말고 **운영에서
읽기 전용으로 산출**한다 (Task 5에서 어차피 운영 값이 정본):
```bash
ssh hypenow "docker exec -i deploy-postgres-raw-1 psql -U crawler -d crawler -v ON_ERROR_STOP=1" < analytics/check/hype-anchor-refit.sql
```

- [ ] **Step 5: 커밋**

```bash
git add analytics/check/hype-anchor-refit.sql analytics/check/hype-anchor-refit.sh
git commit -m "feat(analytics): 하입 스코어 앵커 재적합 재현 쿼리 신설

v2.1 스펙에 앵커 산출 절차가 없어 피드 앵커 미재적합을 아무도 검증할 수
없었다. 기준량은 감쇠 전 Q, 모수는 전체 서빙 코퍼스(스펙 §4·§5-2)."
```

---

### Task 2: v3 회귀 테스트 작성 (실패 확인까지)

스펙 §5-3. **현행 v2.1 함수에서 반드시 실패해야 한다** — 실패하지 않으면 테스트가 변경을 검증하지 못한다는 뜻이다.

단언은 튜닝값에 의존하지 않는 성질 기반으로 쓴다(기존 `02_hype_reels_engage.test.sql` 관용구를 따른다).

**Files:**
- Create: `analytics/test/02_hype_v3_decay_order.test.sql`

- [ ] **Step 1: 실패하는 테스트 작성**

`analytics/test/02_hype_v3_decay_order.test.sql`:

```sql
-- hype_score v3 회귀 테스트: 감쇠는 앵커 매핑 **뒤에** 곱한다.
-- 순수 함수 검증 — 쓰기는 app_setting 임시 오버라이드뿐(run.sh가 BEGIN/ROLLBACK으로 격리).
-- 단언은 앵커·상수 실제값과 무관한 성질만 본다.
DO $$
DECLARE
  d0 bigint; d14 bigint; d28 bigint;
  fresh bigint; old bigint;
  r bigint; f bigint;
BEGIN
  -- 1) 매핑 후 감쇠: 같은 Q에 대해 점수는 경과일에 정확히 0.5^(경과일/halflife) 배.
  --    v2.1(감쇠를 Q에 먼저 곱하고 매핑)에서는 매핑이 비선형이라 이 비율이 성립하지 않는다.
  d0  := analytics.hype_score('reels', 100000, 3000, 200, 10000, 0);
  d14 := analytics.hype_score('reels', 100000, 3000, 200, 10000, 14);
  d28 := analytics.hype_score('reels', 100000, 3000, 200, 10000, 28);
  IF abs(d14 - round(d0 * 0.5)) > 1 THEN
    RAISE EXCEPTION '감쇠가 매핑 뒤에 곱해지지 않음: d0=%, d14=% (기대 %)', d0, d14, round(d0*0.5);
  END IF;
  IF abs(d28 - round(d0 * 0.25)) > 1 THEN
    RAISE EXCEPTION '반감기 2주기 비율 위반: d0=%, d28=% (기대 %)', d0, d28, round(d0*0.25);
  END IF;

  -- 2) 클램프는 감쇠 **전에** 적용한다 — base를 [0,100]으로 자른 뒤 곱해야
  --    "품질 백분위 × 신선도"가 성립한다. 앵커 p99를 크게 넘는 입력으로 확인:
  --    매핑값이 100을 넘으므로 d0은 100이고, 반감기 1주기 뒤는 정확히 그 절반이어야 한다.
  --    (감쇠 후 클램프라면 100보다 큰 base가 절반이 되어 50을 초과한다.)
  fresh := analytics.hype_score('reels', 1000000000, 1000000000, 0, 1000, 0);
  old   := analytics.hype_score('reels', 1000000000, 1000000000, 0, 1000, 14);
  IF fresh <> 100 THEN
    RAISE EXCEPTION '극단 입력이 상한 100에 닿지 않음: %', fresh;
  END IF;
  IF old <> round(fresh * 0.5) THEN
    RAISE EXCEPTION '클램프가 감쇠 뒤에 적용됨: fresh=%, d14=% (기대 %)', fresh, old, round(fresh*0.5);
  END IF;

  -- 3) 타입 동등성: 같은 Q면 타입과 무관하게 같은 점수.
  --    릴스 조회수를 0으로 두면 도달 항이 ln(1)=0이라 Q는 참여 항만 남는다.
  --    e0를 f0와 같게 맞추고 앵커도 두 타입 동일하게 오버라이드하면 두 Q가 완전히 같아진다.
  INSERT INTO app_setting(key,value) VALUES
    ('analytics.hype-reels-e0','0.03'),
    ('analytics.hype-anchor-q-reels-p05','0.05'), ('analytics.hype-anchor-q-feed-p05','0.05'),
    ('analytics.hype-anchor-q-reels-p50','0.60'), ('analytics.hype-anchor-q-feed-p50','0.60'),
    ('analytics.hype-anchor-q-reels-p90','1.60'), ('analytics.hype-anchor-q-feed-p90','1.60'),
    ('analytics.hype-anchor-q-reels-p99','3.00'), ('analytics.hype-anchor-q-feed-p99','3.00');
  r := analytics.hype_score('reels', 0, 1000, 50, 10000, 3);
  f := analytics.hype_score('feed', NULL, 1000, 50, 10000, 3);
  IF r <> f THEN
    RAISE EXCEPTION '타입 동등성 위반: 같은 Q인데 reels=%, feed=%', r, f;
  END IF;
  DELETE FROM app_setting WHERE key='analytics.hype-reels-e0' OR key LIKE 'analytics.hype-anchor-q-%';

  RAISE NOTICE '02_hype_v3_decay_order: 모든 단언 통과';
END $$;
```

- [ ] **Step 2: 테스트를 돌려 실패를 확인**

Run:
```bash
(cd analytics && ./test/run.sh test/02_hype_v3_decay_order.test.sql)
```

Expected: FAIL — `감쇠가 매핑 뒤에 곱해지지 않음: d0=..., d14=...` 예외로 죽는다.
(v2.1 함수는 감쇠를 `Q`에 먼저 곱하므로 비율이 0.5가 되지 않는다.)

컨테이너가 없어 실행 자체가 안 되면 먼저 컨테이너를 띄운다(`docker start crawler-postgres-1` —
`docker compose up`은 빈 컨테이너를 새로 만들므로 쓰지 않는다). colima면 `DOCKER_HOST` 설정이 필요하다.

- [ ] **Step 3: 커밋 (실패하는 테스트를 먼저 남긴다)**

```bash
git add analytics/test/02_hype_v3_decay_order.test.sql
git commit -m "test(analytics): 하입 스코어 v3 감쇠 순서·타입 동등성 회귀 테스트

현행 v2.1 함수에서 실패한다(감쇠를 Q에 먼저 곱해 매핑이 비선형).
클램프가 감쇠 전에 적용되는지도 극단 입력으로 핀한다."
```

---

### Task 3: 함수 본문 변경 (감쇠를 매핑 뒤로 + 앵커 키 개명)

**Files:**
- Modify: `analytics/views/02_serving.sql:18-64` (함수 본문), `:4-17` (헤더 주석)

- [ ] **Step 1: 함수 본문 교체**

`analytics/views/02_serving.sql`의 `CREATE OR REPLACE FUNCTION analytics.hype_score(...)` 블록에서
`s` CTE의 앵커 4쌍 키 이름을 개명하고, `c` CTE와 최종 `SELECT`를 아래로 바꾼다.

앵커 키 개명 (8곳) — `analytics.hype-anchor-reels-p05` → `analytics.hype-anchor-q-reels-p05` 식으로
`reels`/`feed` × `p05`/`p50`/`p90`/`p99` 전부. 기본값도 동시에 교체한다(Task 5에서 확정한 실측값,
초기 반영은 스펙 §4의 값):

```sql
      CASE WHEN content_type='reels'
        THEN COALESCE((SELECT value::numeric FROM app_setting WHERE key='analytics.hype-anchor-q-reels-p05'),0.1373)
        ELSE COALESCE((SELECT value::numeric FROM app_setting WHERE key='analytics.hype-anchor-q-feed-p05'),0.0447) END  AS a05,
      CASE WHEN content_type='reels'
        THEN COALESCE((SELECT value::numeric FROM app_setting WHERE key='analytics.hype-anchor-q-reels-p50'),1.3798)
        ELSE COALESCE((SELECT value::numeric FROM app_setting WHERE key='analytics.hype-anchor-q-feed-p50'),0.6135) END  AS a50,
      CASE WHEN content_type='reels'
        THEN COALESCE((SELECT value::numeric FROM app_setting WHERE key='analytics.hype-anchor-q-reels-p90'),4.5716)
        ELSE COALESCE((SELECT value::numeric FROM app_setting WHERE key='analytics.hype-anchor-q-feed-p90'),1.6320) END  AS a90,
      CASE WHEN content_type='reels'
        THEN COALESCE((SELECT value::numeric FROM app_setting WHERE key='analytics.hype-anchor-q-reels-p99'),10.3883)
        ELSE COALESCE((SELECT value::numeric FROM app_setting WHERE key='analytics.hype-anchor-q-feed-p99'),3.0144) END  AS a99
```

`c` CTE — 감쇠를 떼어내 순수 `Q`만 계산 (컬럼명 `qf` → `q`):

```sql
  c AS (
    SELECT s.*,
      (CASE WHEN content_type='reels'
        THEN s.wr * ln(1 + views::numeric/(COALESCE(followers,0)+1000))
           + s.we * ln(1 + ((likes + comments*3)::numeric/(COALESCE(followers,0)+1000))/s.e0)
        ELSE ln(1 + ((likes + comments*3)::numeric/(COALESCE(followers,0)+1000))/s.f0)
      END) AS q
    FROM s
  )
```

최종 `SELECT` — 매핑 → `[0,100]` 클램프 → 감쇠 → `round`:

```sql
  SELECT CASE
    WHEN likes IS NULL OR comments IS NULL OR (content_type='reels' AND views IS NULL) THEN NULL
    ELSE round(
      GREATEST(LEAST(
        CASE
          WHEN q <= a05 THEN 10*q/NULLIF(a05,0)
          WHEN q <= a50 THEN 10 + 35*(q-a05)/NULLIF(a50-a05,0)
          WHEN q <= a90 THEN 45 + 35*(q-a50)/NULLIF(a90-a50,0)
          WHEN q <= a99 THEN 80 + 17*(q-a90)/NULLIF(a99-a90,0)
          ELSE 97 + 3*(q-a99)/NULLIF(a99-a90,0)
        END, 100), 0)
      * power(0.5, GREATEST(elapsed_days,0)/hl)
    )::bigint
  END
  FROM c
```

- [ ] **Step 2: 헤더 주석 갱신 (4~17행)**

v2 설명을 v3로 고친다. 반드시 담을 내용:
- v3(2026-07-30): 감쇠를 앵커 매핑 뒤로 이동. 앵커는 **감쇠 전 Q** 기준·**전체 서빙 코퍼스** 적합.
- 왜: `qf`에 앵커를 맞추면 캘리브레이션이 코퍼스 연령 구성에 오염된다 — 피드가 릴스보다 옛날 꼬리가
  두꺼워 타입별로 다르게 눌렸고 그게 피드 편향의 구조적 원인이었다. 앵커 재적합만으로는 적합 모수를
  벗어나면 다시(반대 방향으로) 어긋난다.
- 점수 = `clamp(map_Q(Q), 0, 100) × 0.5^(경과일/halflife)` — 클램프는 감쇠 **전**.
- 앵커 재산출은 `analytics/check/hype-anchor-refit.sh`로 (재현 절차를 저장소에 둔 이유 포함).
- 키 목록 15행: `hype-anchor-{reels,feed}-{...}` → `hype-anchor-q-{reels,feed}-{...}`. 기준량이 `qf`→`Q`로
  바뀌었으므로 구 키 값은 무시된다는 점을 명시(옛 스펙 값을 구 키에 넣어 조용히 망가지는 것을 막는다).
- 스펙 경로: `docs/superpowers/specs/2026-07-30-hype-score-v3-decay-after-mapping-design.md`

- [ ] **Step 3: 신설 테스트를 돌려 통과 확인**

Run:
```bash
(cd analytics && ./test/run.sh test/02_hype_v3_decay_order.test.sql)
```

Expected: `PASS: test/02_hype_v3_decay_order.test.sql` + `ALL GREEN`.

- [ ] **Step 4: 커밋**

```bash
git add analytics/views/02_serving.sql
git commit -m "feat(analytics): 하입 스코어 v3 — 감쇠를 앵커 매핑 뒤로

qf에 앵커를 맞추면 캘리브레이션이 코퍼스 연령 구성에 오염된다. 피드는
릴스보다 옛날 꼬리가 두꺼워 타입별로 다르게 눌렸고, 그게 발굴 목록
피드 편향의 구조적 원인이었다.

- 점수 = clamp(map_Q(Q),0,100) × 0.5^(경과일/halflife). 클램프는 감쇠 전
  (base가 [0,100]이어야 '품질 백분위 × 신선도'가 성립).
- 앵커 8개를 Q 기준·전체 서빙 코퍼스 적합값으로 교체.
- app_setting 앵커 키를 hype-anchor-q-*로 개명 — 기준량이 qf→Q로 바뀌어
  구 키에 옛 값을 넣으면 조용히 망가진다."
```

---

### Task 4: 기존 테스트 갱신

앵커 키 개명으로 `02_serving.test.sql`의 앵커 오버라이드 단언이 무력화된다(구 키를 넣어도 함수가 읽지 않으므로 `base`와 같은 값이 나와 `<` 단언이 실패한다). 주석의 v2 표기도 고친다.

**Files:**
- Modify: `analytics/test/02_serving.test.sql:59`, `:97-101`

- [ ] **Step 1: 앵커 오버라이드 키 개명 + 주석 갱신**

59행 주석: `hype_score v2 (연속 절대식·타입별 앵커)` → `hype_score v3 (Q 기준 앵커·매핑 후 감쇠)`.

97~101행 블록의 키를 개명하고 주석의 `qf`를 `Q`로 고친다:

```sql
  -- 앵커 오버라이드: p50을 올리면 같은 Q가 더 낮은 구간에 놓인다
  base := analytics.hype_score('reels', 50000, 1000, 50, 10000, 3);
  INSERT INTO app_setting(key,value) VALUES ('analytics.hype-anchor-q-reels-p05','1.0'),
    ('analytics.hype-anchor-q-reels-p50','5.0'),('analytics.hype-anchor-q-reels-p90','8.0'),('analytics.hype-anchor-q-reels-p99','12.0');
  ASSERT analytics.hype_score('reels', 50000, 1000, 50, 10000, 3) < base, '앵커 p50↑ → 같은 Q 더 낮은 점수';
  DELETE FROM app_setting WHERE key LIKE 'analytics.hype-anchor-%';
```

(`DELETE ... LIKE 'analytics.hype-anchor-%'`는 개명 후 키도 접두사가 같으므로 그대로 둔다.
97행 이전의 `p05` INSERT 행이 원본에 있으면 같이 개명한다 — Step 2에서 실제 파일을 보고 맞춘다.)

- [ ] **Step 2: 원본 97~101행을 실제로 확인해 위 블록과 일치시킨다**

Run:
```bash
sed -n '95,102p' analytics/test/02_serving.test.sql
```

원본의 INSERT 행 구성(키 개수·값)을 그대로 두고 **키 이름만** 개명한다. 값이나 단언을 바꾸지 않는다.

- [ ] **Step 3: 하입 스코어 관련 테스트 전체 실행**

Run:
```bash
(cd analytics && ./test/run.sh test/02_serving.test.sql test/02_hype_reels_engage.test.sql test/02b_reels_pin.test.sql test/02_hype_v3_decay_order.test.sql test/10_account_detail.test.sql)
```

Expected: 5개 모두 `PASS` + `ALL GREEN`.

`02_hype_reels_engage.test.sql`(조회수 단조성·저조회수 뭉침)과 `10_account_detail.test.sql`(계정 평균
일관성)은 성질 기반이라 v3에서도 통과해야 한다. **실패하면 v3 산식이 의도치 않은 성질을 깼다는
뜻이므로 테스트를 고치지 말고 함수를 재검토한다.**

- [ ] **Step 4: 커밋**

```bash
git add analytics/test/02_serving.test.sql
git commit -m "test(analytics): 앵커 오버라이드 키를 hype-anchor-q-*로 개명

v3에서 앵커 기준량이 qf→Q로 바뀌며 키가 개명됐다. 단언 내용은 그대로."
```

---

### Task 5: 운영 실측으로 앵커값 확정

Task 3에서 넣은 기본값은 스펙 §4의 측정값이다. Task 1의 재현 쿼리로 **같은 값이 재현되는지** 확인하고, 어긋나면 실측값으로 교체한다(쿼리가 정본).

**Files:**
- Modify: `analytics/views/02_serving.sql` (앵커 기본값 8개, 필요시)

- [ ] **Step 1: 운영에서 읽기 전용으로 앵커 산출**

Run:
```bash
ssh hypenow "docker exec -i deploy-postgres-raw-1 psql -U crawler -d crawler -v ON_ERROR_STOP=1" < analytics/check/hype-anchor-refit.sql
```

Expected: feed·reels 2행. 스펙 §4 기대값 — feed `0.0447/0.6135/1.6320/3.0144`(n≈35,657),
reels `0.1373/1.3798/4.5716/10.3883`(n≈81,943).

- [ ] **Step 2: 차이를 판정**

각 앵커점이 기대값의 ±10% 안이면 스펙 값을 유지한다(모수가 하루 단위로 자라므로 소폭 차이는 정상).
±10%를 넘으면 **실측값으로 `02_serving.sql` 기본값을 교체**하고, 어긋난 이유를 한 줄로 기록한다
(가장 흔한 원인: Task 1 Step 2의 소스 뷰 이름을 잘못 잡아 서빙과 다른 지표를 읽는 것).

- [ ] **Step 3: 값을 교체했다면 테스트 재실행 후 커밋**

Run:
```bash
(cd analytics && ./test/run.sh test/02_hype_v3_decay_order.test.sql test/02_serving.test.sql)
```

Expected: `ALL GREEN` (단언이 성질 기반이라 앵커값이 바뀌어도 통과해야 한다).

```bash
git add analytics/views/02_serving.sql
git commit -m "fix(analytics): 하입 스코어 앵커를 재적합 쿼리 실측값으로 확정"
```

값이 기대 범위 안이라 교체할 게 없으면 이 스텝은 건너뛴다.

---

### Task 6: stale 주석·문서 갱신

**Files:**
- Modify: `contract-analysis/src/main/java/com/celfit/contract/analysis/Content.java:8`
- Modify: `contract-analysis/src/main/java/com/celfit/contract/analysis/ContentMetricSnapshot.java:8-9`
- Modify: `ARCHITECTURE.md` (§5 작업 트랙 표, §7 결정 기록)

- [ ] **Step 1: 계약 record의 Javadoc 갱신**

두 파일의 `hypeScore` Javadoc이 아직 **v1** 방식(`cbrt(도달×참여질×신선도)×100`)을 설명한다 — v2에서
이미 폐기된 내용이라 지금도 틀렸다. 산식 정본이 SQL임을 명시하는 형태로 바꾼다:

```java
 * hypeScore: 0~100. 산식 정본은 analytics.hype_score() (analytics/views/02_serving.sql) —
 * clamp(타입별 앵커 매핑(Q), 0, 100) × 0.5^(경과일/halflife). Java에는 계산 로직이 없다(미러 값 통과).
```

정확한 문구는 각 파일의 기존 Javadoc 스타일에 맞춘다. **로직 변경 없음.**

- [ ] **Step 2: 컴파일 확인**

Run:
```bash
./gradlew :contract-analysis:compileJava -q
```

Expected: 성공(출력 없음). 주석만 바꿨으므로 실패하면 문법 오류다.

- [ ] **Step 3: ARCHITECTURE.md 갱신**

- §5 작업 트랙 표에 새 트랙 행을 추가한다. 트랙 문자는 **표의 마지막 문자 다음**을 쓴다(현재 X·Y까지
  사용 중이므로 Z일 가능성이 높지만, 반드시 표를 열어 확인한다).
  행 내용: 하입 스코어 v3(감쇠를 매핑 뒤로 — 피드/릴스 타입 편향 해소), 상태·스펙·계획 경로.
- §7 결정 기록에 항목을 추가한다. 담을 것: 관찰(발굴 목록 피드 편향) → 실측(피드 ≥70점 7.61% vs
  릴스 3.98%, 상위 50 피드비율 중앙값 0.70 vs 전체 0.18) → 원인(앵커를 감쇠 후 qf에 적합해
  캘리브레이션이 코퍼스 연령 구성에 오염) → 채택하지 않은 안(앵커 재적합만으로는 실서빙 모수에서
  과잉교정으로 뒤집힘, 실측 확인) → 결정(감쇠를 매핑 뒤로, 앵커는 Q 기준·전체 서빙 코퍼스) →
  범위 외(역-U형 잔차·표본 하한·followers 시점 결함).

- [ ] **Step 4: 커밋**

```bash
git add contract-analysis ARCHITECTURE.md
git commit -m "docs: 하입 스코어 v3 반영 — 계약 Javadoc·ARCHITECTURE 트랙·결정 기록

계약 record Javadoc이 v2에서 이미 폐기된 v1 산식(cbrt 방식)을 설명하고
있었다. 산식 정본이 SQL임을 명시하는 형태로 교체."
```

---

### Task 7: 전체 검증 후 PR

**Files:** 없음(검증·PR만)

- [ ] **Step 1: SQL 하니스 전체 실행**

Run:
```bash
(cd analytics && ./test/run.sh)
```

Expected: 모든 `*.test.sql`이 `PASS` + `ALL GREEN`.

- [ ] **Step 2: Gradle 전체 테스트**

Run:
```bash
./gradlew test
```

Expected: `BUILD SUCCESSFUL`. (Testcontainers PostgreSQL을 쓰므로 Docker가 떠 있어야 한다.)

이번 변경은 SQL 함수와 주석뿐이라 Java 테스트가 깨질 이유가 없다. 깨지면 원인을 찾아 고치고,
"관계없는 기존 실패"라고 단정하기 전에 `git stash`로 재현 여부를 확인한다.

- [ ] **Step 3: 마이그레이션 가드 확인**

Run:
```bash
git diff origin/develop --stat -- '*/db/migration/*'
```

Expected: 출력 없음. 이번 변경에 Flyway 마이그레이션은 없다(앵커 기준값의 단일 소스는 함수 기본값 —
app_setting 시드를 넣지 않는다, 스펙 §5-1). 출력이 있으면 스펙과 어긋난 것이므로 되돌린다.

- [ ] **Step 4: PR 생성**

```bash
git push -u origin feat/hype-score-v3-decay-after-mapping
```

PR은 **develop 대상**으로 만든다. 본문에 담을 것:
- 문제와 실측(§1), 원인 규명(§2), 채택하지 않은 안(§3-1)
- 산식 변경 전후 식
- **배포 순서 주의: 뷰 선적용 → 미러 → 스팟체크.** 이 PR 머지만으로는 운영 점수가 바뀌지 않는다
  (뷰는 수동 적용). 배포 런북은 Task 8.
- 전 콘텐츠·전 계정 점수가 이동하고 발굴 목록 순위가 바뀐다 → **프론트 통지 필요**

---

### Task 8: 운영 반영 (사용자 승인 후 별도 실행)

**이 태스크는 PR 머지 후, 사용자 승인을 받고 실행한다.** 운영 데이터 산출물이 전면 이동하므로 자동 실행하지 않는다.

- [ ] **Step 1: 반영 전 기준선 기록 (읽기 전용)**

타입별 `≥70점 비율`, `avg_hype_score` 상위 50 계정 핸들·피드비율 중앙값, 전체 계정 점수 중앙값을
스냅샷으로 남긴다. Step 4의 스팟체크가 이 기준선과 비교할 대상이다.

- [ ] **Step 2: 뷰 적용**

```bash
cat analytics/views/*.sql | ssh hypenow "docker exec -i deploy-postgres-raw-1 psql -U crawler -d crawler -v ON_ERROR_STOP=1 --single-transaction"
```

반드시 `origin/develop` 기준 워크트리에서 실행한다 — 다른 세션의 뷰 변경을 되덮은 사고 전력이 있다.

- [ ] **Step 3: 미러 잡 실행**

```bash
ssh hypenow "curl -sS -X POST 127.0.0.1:8082/ui/jobs/mirror"
```

- [ ] **Step 4: 스팟체크**

Step 1 기준선과 비교해 확인할 것:
- **타입별 `≥70점` 비율이 수렴**했는가 (피드 7.61% / 릴스 3.98% → 두 값이 가까워져야 한다).
  이게 이번 작업의 성공 판정이다.
- 상위 50 계정 피드비율 중앙값이 0.70에서 전체 중앙값(0.18)에 가까워졌는가.
- 계정 점수 이동 폭(평균·중앙값·최대 하락)과 상위 50 명단 교체 수 — 프론트 통지에 넣을 수치.

- [ ] **Step 5: 프론트 통지**

Step 4에서 실측한 이동 폭·순위 교체 규모를 포함해 전달한다. 점수 절대값과 순위가 동시에 움직인다.

**롤백:** 이전 함수 정의(`git show origin/develop~1:analytics/views/02_serving.sql`의 함수 블록)를
재적용하고 미러를 다시 돌린다.

---

## 자체 검토

**스펙 커버리지:** §3 결정 → Task 3 / §4 앵커·모수 → Task 3 Step 1 + Task 5 / §5-1 함수 변경 → Task 3 /
§5-2 재현 쿼리 → Task 1 / §5-3 테스트 → Task 2(신설)·Task 4(기존 갱신) / §5-4 롤아웃 → Task 8 /
§6 영향·통지 → Task 7 Step 4 + Task 8 Step 5 / §7 범위 외 → Task 6 Step 3(§7 결정 기록에 명시).
빠진 요구사항 없음.

**미확정 항목 2건** (구현 중 실제 파일을 열어 확정하도록 스텝에 명시):
- Task 1 Step 2 — 재적합 쿼리의 소스 뷰·컬럼 이름은 `v_contents` 정의에서 확인해 맞춘다.
- Task 6 Step 3 — ARCHITECTURE §5의 새 트랙 문자는 표를 열어 확인한다.

**타입 일관성:** 앵커 키는 전 태스크에서 `analytics.hype-anchor-q-{reels,feed}-{p05,p50,p90,p99}`로
일치. 함수 내부 컬럼명은 `qf`→`q`로 Task 3에서 일괄 교체, Task 2·4의 테스트는 컬럼명에 의존하지 않는다.
