# timely 판정 캘린더일 정합 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

> 상태: ✅ 구현/실행/반영됨(2026-07-30) — 소급 런북(§Task 4)까지 실행 완료. 스펙:
> `docs/superpowers/specs/2026-07-28-timely-calendar-alignment-design.md`. 07-30 소급 실행 결과:
> `v_contents` 138,755행 추출(timely 10,651 / 비timely 128,104) → dry-run(a_승격 2,822 /
> b_강등 4,423 / c_추출누락 6,343 미변경 / 랭킹모수 전 6,678) → COMMIT(UPDATE 2,822+4,423,
> 항등식 mismatch=0 확인) → **랭킹 모수 6,678 → 5,755**. 상세는
> [DECISIONS.md](../../../../DECISIONS.md) · [docs/tracks/O-timely-캘린더일-정합.md](../../../tracks/O-timely-캘린더일-정합.md)
> 참조.

**Goal:** `ContentAnalysisJob`의 timely 판정을 raw 후보 뷰(`analytics.v_analysis_candidates`, 캘린더일 KST 기준)로 단일화하고, 기존 `content_analyses.metric_timeliness` 마킹을 양방향 소급 정정한다.

**Architecture:** 잡의 후보 선정을 analysis 미러 기반 간격식 SQL 2본에서 "raw 후보 뷰 조회 + analysis 제외 셋(기분석·댓글 게이트·미러 존재) Java diff"로 교체 — timely 수식은 뷰 한 곳에만 남는다. 성숙·최근12 윈도우·창닫힘 게이트는 뷰 소관이 되므로 해당 Java 테스트는 삭제하고(SQL 하니스 04가 커버) 잡 테스트는 후보 뷰 대역(fixture 테이블 기반 뷰)으로 재배선한다. 소급은 배포 후 별도 실행하는 런북(§Task 5)으로.

**Tech Stack:** Java 21 / Spring JdbcTemplate / Testcontainers(PostgreSQL) / psql 런북

**전제 지식:**
- 잡은 이미 `raw`(JdbcTemplate)·`analysis`(DataSource) 둘 다 가진다 — baseline을 raw 뷰에서 읽는 기존 패턴 그대로. `JobConfig` 배선 무변경.
- 테스트 하니스는 단일 Postgres 컨테이너에 analysis 마이그레이션 + `analytics` 스키마의 fixture 기반 대역 뷰(`v_analysis_baseline` 등)를 만들어 쓴다. 후보 뷰도 같은 패턴으로 대역을 추가한다.
- 운영 뷰의 `timely` 컬럼: 캡처 캘린더일(KST) ∈ [업로드일+pin, 업로드일+pin+slack). 뷰는 `WHERE timely OR in_window`라 timely=false 행 = 윈도우 백필 후보.

---

### Task 1: ContentAnalysisJobTest 재배선 — 후보 뷰 대역 (RED)

**Files:**
- Modify: `analytics/src/test/java/com/celfit/analytics/analyze/ContentAnalysisJobTest.java`

- [ ] **Step 1-1: setUp에 후보 뷰 대역 추가**

`setUp()`의 raw 대역 구획(`v_analysis_account_baseline` 생성 직후)에 추가:

```java
// raw 대역: 후보 뷰(v_analysis_candidates)와 같은 소비 컬럼의 fixture 기반 뷰 —
// 캘린더일 timely 판정·성숙·윈도우 게이트는 뷰 소관(SQL 하니스 04가 검증)이라
// 잡 테스트는 뷰가 주는 (short_code, timely) 결과만 신뢰하고 소비한다 (07-28 정합).
db.update("""
		CREATE TABLE analytics.candidates_fixture (
		    short_code         text PRIMARY KEY,
		    timely             boolean NOT NULL,
		    metric_captured_at timestamptz
		)""");
db.update("""
		CREATE VIEW analytics.v_analysis_candidates AS SELECT * FROM analytics.candidates_fixture""");
db.update("""
		INSERT INTO analytics.candidates_fixture VALUES
		  ('post_a', true, now() - interval '6 days 18 hours'),
		  ('post_b', true, now() - interval '6 days 6 hours'),
		  ('post_c', true, now() - interval '6 days 12 hours')""");
```

기존 `contents`/`content_comments`/`comment_classifications` 시드는 그대로 둔다(`analyzeOne`의 데이터 소스). setUp 주석의 "metric_captured_at = 게시 +3.5일 — 제때…" 두 줄은 "후보 자격은 fixture(timely 컬럼)가 결정 — contents는 analyzeOne이 읽는 미러 대역" 취지로 교체.

- [ ] **Step 1-2: 뷰 소관이 된 게이트 테스트 8개 삭제**

아래 테스트는 검증 대상 로직이 Java에서 사라지고 뷰로 이관된다. 각각 SQL 하니스 `analytics/test/04_analysis_candidates.test.sql`의 대응 케이스가 이미 커버:

| 삭제할 테스트 | 하니스 대응 |
|---|---|
| `게시_후_3일_미경과_콘텐츠는_대상에서_제외된다` | dummy_op(창 미완료 제외) |
| `숙성_일수는_app_setting으로_조정된다` | (설정 자체가 잡에서 미사용화 — §Task 3 Step 3-3 참조) |
| `posted_at이_NULL인_콘텐츠는_대상에서_제외된다` | 뷰 WHERE의 posted_at 연산이 NULL 전파로 제외 |
| `고정_지표가_미성숙_스냅샷이면_대상에서_제외된다` | dummy_op·rn(미성숙 제외) |
| `지표_수집_시각_미상_게시물은_대상에서_제외된다` | 뷰 timely LATERAL이 스냅샷 부재 시 false |
| `늦크롤이면서_윈도우_밖이면_여전히_제외된다` | recent-window=1 케이스 |
| `늦크롤이면서_제때창이_아직_열려있으면_runLateBackfill에서도_제외된다` | dummy_op |
| `제때_크롤_판정_여유는_app_setting으로_조정된다` | slack=2 블록 |
| `늦크롤_백필_게시물도_윈도우가_닫히면_runLateBackfill_대상에서도_제외된다` | recent-window=0 킬스위치 — **Task 4에서 하니스에 신규 추가** |

(마지막 행 포함 총 9개 삭제.)

- [ ] **Step 1-3: 늦크롤 테스트 2개를 fixture 방식으로 재작성**

```java
@Test
void 뷰가_NOT_timely로_준_후보는_runLateBackfill이_분석하고_late_backfill로_마킹한다() {
	// 07-28 정합: 늦크롤 여부는 뷰의 timely 컬럼이 정본 — 잡은 플래그를 그대로 소비해 마킹한다.
	// timely·backfill 진입점은 WHERE timely = ? 로 상호 배타(같은 뷰의 서로소 분할).
	db.update("UPDATE analytics.candidates_fixture SET timely = false WHERE short_code = 'post_a'");

	int timelyProcessed = job.run().processed();
	int backfillProcessed = job.runLateBackfill().processed();

	assertEquals(1, timelyProcessed); // post_b만
	assertEquals(1, backfillProcessed); // post_a
	assertEquals("late_backfill", db.queryForObject(
			"SELECT metric_timeliness FROM content_analyses WHERE short_code = 'post_a'", String.class));
}

@Test
void timely_후보는_runLateBackfill_대상이_아니다() {
	// setUp 기본 fixture는 전부 timely=true — backfill 진입점은 아무것도 집지 않아야
	// 두 진입점의 short_code 집합이 서로소가 되고 content_analyses INSERT 경합이 없다.
	int backfillProcessed = job.runLateBackfill().processed();

	assertEquals(0, backfillProcessed);
	assertEquals(0L, db.queryForObject("SELECT count(*) FROM content_analyses", Long.class));
}
```

기존 `늦크롤이라도_최근_윈도우_안이면_runLateBackfill이_분석하고_late_backfill로_마킹한다`·`timely이면서_최근_윈도우_안인_콘텐츠는_runLateBackfill_쿼리에서_제외된다`는 위 둘로 대체(삭제).

- [ ] **Step 1-4: 미러 부재 스킵 신규 테스트**

```java
@Test
void 후보가_미러에_없으면_스킵하고_실패로_세지_않는다() {
	// 라이브 뷰(후보)와 미러(19:30 스냅샷) 사이 간극 가드 — analyzeOne이 미러에서 행을
	// 못 찾아 실패 카운트를 오염시키는 대신 대상에서 조용히 빠지고, 다음 미러 후 자연 재대상.
	db.update("""
			INSERT INTO analytics.candidates_fixture VALUES
			  ('post_ghost', true, now() - interval '1 hour')""");

	var result = job.run();

	assertEquals(2, result.processed()); // post_a·post_b — post_ghost는 스킵
	assertEquals(0, result.failed());
	assertFalse(insightCalls.stream().anyMatch(c -> c.shortCode().equals("post_ghost")));
}
```

- [ ] **Step 1-5: post_0 시드 테스트 2곳에 fixture 행 추가**

`최근창_밖_콘텐츠는_계정_평균을_앵커로_분석된다`·`쿼타_소진_플래그가_서면_이후_대상은_LLM_호출_없이_스킵된다`의 `INSERT INTO contents … post_0` 직후에 각각 추가:

```java
db.update("""
		INSERT INTO analytics.candidates_fixture VALUES
		  ('post_0', true, now() - interval '6 days 20 hours')""");
```

(쿼타 테스트 쪽은 기존 contents 시드의 captured가 `- interval '6 days 22 hours'`이므로 fixture도 `'6 days 22 hours'`로 동일하게 — 최신순 검증이 어긋나지 않게 contents와 fixture의 captured를 항상 맞춘다.)

- [ ] **Step 1-6: 진행률 테스트의 대상 1건 고정 방식 교체**

`진행률을_보고한다`의 `db.update("UPDATE contents SET posted_at = …")` 한 줄을 다음으로 교체(주석의 "숙성 가드 미달" 문구도 "후보 fixture에서 제거" 취지로 수정):

```java
db.update("DELETE FROM analytics.candidates_fixture WHERE short_code = 'post_b'");
```

- [ ] **Step 1-7: 클래스 javadoc 갱신**

클래스 상단 javadoc의 `⑥ B3 숙성 가드(게시 후 3일)` 항목을 `⑥ 후보 자격은 raw 후보 뷰가 정본(07-28 캘린더일 정합) — 잡은 timely 플래그 소비·마킹만`으로 교체.

- [ ] **Step 1-8: RED 확인**

Run: `cd /Users/woomin/Project/hypenow-backend/.worktrees/timely-calendar && ./gradlew :analytics:test --tests "com.celfit.analytics.analyze.ContentAnalysisJobTest"`
Expected: FAIL — 신규·재작성 테스트들이 실패한다(기존 잡은 fixture 뷰를 읽지 않고 미러 기반 간격식으로 후보를 뽑으므로 `post_ghost` 스킵·`timely=false` fixture 반영 등이 동작하지 않음). 컴파일은 통과해야 한다.

- [ ] **Step 1-9: Commit**

```bash
git add analytics/src/test/java/com/celfit/analytics/analyze/ContentAnalysisJobTest.java
git commit -m "test(analytics): ContentAnalysisJob 테스트를 후보 뷰 대역으로 재배선 (RED)"
```

---

### Task 2: ContentAnalysisJob 구현 교체 (GREEN)

**Files:**
- Modify: `analytics/src/main/java/com/celfit/analytics/analyze/ContentAnalysisJob.java`

- [ ] **Step 2-1: 후보 SQL 교체**

`TIMELY_SQL`·`LATE_BACKFILL_SQL` 상수(및 그 주석 블록)를 삭제하고 다음으로 교체:

```java
// 후보 자격은 raw 후보 뷰가 정본(07-28 캘린더일 정합 — 뷰 04 주석 참조): 캘린더일(KST)
// timely 판정·성숙(창닫힘)·최근 N개 윈도우 게이트 전부 뷰 소관. 잡은 timely 플래그로 두
// 진입점을 서로소 분할(WHERE timely = ?)하고 마킹에 그대로 쓴다. 구 간격식(캡처가 업로드
// +pin~+pin+slack일 '시간 간격' 안) 판정은 뷰의 캘린더일 정정(07-20)과 갈라져 일 수백 건이
// late_backfill로 새던 원인이라 제거 — 수식은 뷰 한 곳에만 둔다.
// '이미 분석됨'·댓글 게이트 제외는 analysis DB 상태라 SQL 조인이 불가 — Java 셋 대조(diff)로
// 뺀다(뷰 주석의 원 설계: "'이미 분석됨' 제외·정렬 정책은 Java 몫").
private static final String CANDIDATES_SQL = """
		SELECT short_code
		FROM analytics.v_analysis_candidates
		WHERE timely = ?
		ORDER BY metric_captured_at DESC NULLS LAST, short_code""";
```

- [ ] **Step 2-2: run()/runLateBackfill() 단순화**

```java
/**
 * @return 잡 실행 결과 (처리·실패 건수, 일 한도 이월 여부)
 *
 * <p>후보 뷰의 timely 후보 전량(LIMIT 없음 — 실질 상한은 LLM 429 quota).
 */
public JobResult run() {
	return runQuery(true, reporter);
}

/**
 * @return 잡 실행 결과 (처리·실패 건수, 일 한도 이월 여부)
 *
 * <p>후보 뷰의 NOT timely 후보(= 최근 N개 윈도우 안 늦크롤) 전량(LIMIT 없음).
 * run()과 상호 배타 — 같은 뷰의 timely 컬럼으로 서로소 분할이라 같은 short_code가
 * 두 진입점에 동시에 잡히지 않는다.
 */
public JobResult runLateBackfill() {
	return runQuery(false, backfillReporter);
}
```

- [ ] **Step 2-3: runQuery 대상 선정 교체**

`runQuery` 시그니처를 `private JobResult runQuery(boolean timely, ProgressReporter progress)`로 바꾸고, 본문 앞부분(기존 `analysis.query(sql, …)` 대상 적재)을 다음으로 교체 — 이후의 병렬 처리·쿼터 이월·진행률 로직은 무변경:

```java
Baselines baselines = loadBaselines();
List<String> candidates = new ArrayList<>();
raw.query(CANDIDATES_SQL, rs -> {
	candidates.add(rs.getString(1));
}, timely);
// analysis 쪽 제외 셋 3종 — 후보 수만·분석 누적 8만 스케일이라 통짜 로드가 충분히 싸다.
Set<String> analyzed = new HashSet<>(
		analysis.queryForList("SELECT short_code FROM content_analyses", String.class));
// 댓글이 미러됐는데 분류가 아직인 콘텐츠는 댓글 인사이트 입력이 미완이라 보류(기존 게이트 유지)
Set<String> commentBlocked = new HashSet<>(analysis.queryForList("""
		SELECT DISTINCT m.short_code FROM content_comments m
		WHERE NOT EXISTS (SELECT 1 FROM comment_classifications k WHERE k.short_code = m.short_code)""",
		String.class));
// 라이브 후보 뷰와 미러(전날 19:30 스냅샷) 간극 가드 — 미러에 아직 없는 후보를 analyzeOne이
// 조회 실패(실패 카운트 오염)로 만들지 않고 스킵한다. 다음 미러 후 자연 재대상.
Set<String> mirrored = new HashSet<>(
		analysis.queryForList("SELECT short_code FROM contents", String.class));
List<String> targets = new ArrayList<>();
int mirrorMissing = 0;
for (String shortCode : candidates) {
	if (analyzed.contains(shortCode) || commentBlocked.contains(shortCode)) {
		continue;
	}
	if (!mirrored.contains(shortCode)) {
		mirrorMissing++;
		continue;
	}
	targets.add(shortCode);
}
if (mirrorMissing > 0) {
	log.info("미러 부재 후보 {}건 스킵 — 다음 미러 후 자연 재대상", mirrorMissing);
}
```

(이후 `String model = …`부터는 기존 코드 그대로, `targets` 사용.)

- [ ] **Step 2-4: 잔재 정리**

- 클래스 javadoc의 timely/백필 진입점 설명(27~28행 근방)을 "후보 뷰의 timely 컬럼으로 서로소 분할, 예산·스케줄 별도" 취지로 갱신.
- 안 쓰게 된 import(`없으면 생략`) 정리. `settings.metricPinDays()`/`analyzeTimelySlackDays()`/`analyzeMaturityDays()` 호출이 이 파일에서 전부 사라졌는지 확인.
- `AnalyticsSettings.analyzeMaturityDays()`에 javadoc 한 줄 추가: `/** 07-28 캘린더일 정합 후 잡에서 미사용 — 후보 성숙 가드는 04 뷰 소관. 키 호환을 위해 유지. */`

- [ ] **Step 2-5: GREEN 확인**

Run: `./gradlew :analytics:test --tests "com.celfit.analytics.analyze.ContentAnalysisJobTest"`
Expected: PASS (전체)

- [ ] **Step 2-6: analytics 모듈 전체 테스트**

Run: `./gradlew :analytics:test`
Expected: PASS — `AnalyticsJobServiceTest`·`AdminUiControllerTest`는 잡을 mock하므로 영향 없음. 실패 시 해당 테스트의 가정(예: 시그니처)을 확인.

- [ ] **Step 2-7: Commit**

```bash
git add analytics/src/main/java/com/celfit/analytics/analyze/ContentAnalysisJob.java analytics/src/main/java/com/celfit/analytics/config/AnalyticsSettings.java
git commit -m "feat(analytics): timely 판정을 후보 뷰(캘린더일)로 단일화 — 간격식 이원화 제거"
```

---

### Task 3: SQL 하니스에 recent-window=0 킬스위치 케이스 추가

**Files:**
- Modify: `analytics/test/04_analysis_candidates.test.sql`

Java 테스트 삭제(Task 1)로 잃는 "recent-window=0이면 백필 전량 차단" 회귀를 뷰 층에서 보강한다.

- [ ] **Step 3-1: 케이스 추가**

기존 `recent-window=1` 블록(107행 근방) 뒤에, 그 블록의 관용(UPDATE 후 재단언)을 그대로 따라 추가:

```sql
-- 킬스위치: recent-window=0이면 늦크롤 백필 후보가 전량 차단된다 (timely 후보만 남음).
UPDATE app_setting SET value = '0' WHERE key = 'analytics.recent-window';
DO $$
BEGIN
  ASSERT NOT EXISTS (SELECT 1 FROM analytics.v_analysis_candidates WHERE NOT timely),
    'recent-window=0인데 timely=false 백필 후보가 남아 있음 (킬스위치 실패)';
  ASSERT EXISTS (SELECT 1 FROM analytics.v_analysis_candidates WHERE timely),
    'recent-window=0이 timely 후보까지 차단함 (킬스위치 과차단)';
END $$;
```

주의: 앞 블록이 `INSERT`로 키를 넣었는지 확인하고, 이미 있으면 위처럼 `UPDATE`, 없으면 `INSERT`. 파일 끝의 정리(cleanup) 관용이 있으면 따른다.

- [ ] **Step 3-2: 하니스 실행**

Run: `PG_CONTAINER=crawler-postgres-1 analytics/test/run.sh test/04_analysis_candidates.test.sql` (로컬 colima Docker의 실데이터 postgres 컨테이너 필요 — 컨테이너명 다르면 `docker ps`로 확인해 `PG_CONTAINER` 조정)
Expected: `04_analysis_candidates.test.sql OK` (전 assert 통과)

- [ ] **Step 3-3: Commit**

```bash
git add analytics/test/04_analysis_candidates.test.sql
git commit -m "test(analytics): 04 하니스에 recent-window=0 킬스위치 케이스 추가"
```

---

### Task 4: 소급 런북 — 계획 문서에 포함 (실행은 배포 후 별도)

**Files:**
- 이 문서의 본 섹션이 런북 정본이다. 코드 변경 없음 — 리뷰만 하고 커밋은 Task 5와 함께.

**실행 시점**: PR 머지 → CD 배포 → 다음 새벽 배치(analyze 20:00Z) 정상 확인 **후**, 사용자 확인 받고 실행.

- [ ] **Step 4-1 (배포 후): 추출 — raw에서 전 콘텐츠 캘린더일 timely 계산**

후보 뷰는 caption·윈도우 게이트로 기분석 행 일부가 빠지므로, 뷰의 LATERAL 판정식만 떼어 **v_contents 전 행**에 적용한다(1회성 스크립트라 수식 복제 허용 — 뷰 04의 83~91행과 동일식). `ssh hypenow`에서:

```bash
docker exec deploy-postgres-raw-1 sh -c 'psql -U $POSTGRES_USER -d crawler -Atc "
SELECT v.short_code||E'"'"'\t'"'"'||EXISTS (
  SELECT 1
  FROM analytics.v_serving_content sc
  JOIN analytics.content_snapshot_cache s USING (content_id)
  WHERE sc.short_code = v.short_code
    AND s.captured_at >= (((v.posted_at AT TIME ZONE '"'"'Asia/Seoul'"'"')::date
          + COALESCE((SELECT value::int FROM app_setting WHERE key = '"'"'analytics.metric-pin-days'"'"'), 3)
        )::timestamp AT TIME ZONE '"'"'Asia/Seoul'"'"')
    AND s.captured_at <  (((v.posted_at AT TIME ZONE '"'"'Asia/Seoul'"'"')::date
          + COALESCE((SELECT value::int FROM app_setting WHERE key = '"'"'analytics.metric-pin-days'"'"'), 3)
          + COALESCE((SELECT value::int FROM app_setting WHERE key = '"'"'analytics.analyze-timely-slack-days'"'"'), 1)
        )::timestamp AT TIME ZONE '"'"'Asia/Seoul'"'"')
    AND s.likes IS NOT NULL AND s.comments_count IS NOT NULL
    AND (sc.content_type <> '"'"'REELS'"'"' OR s.views IS NOT NULL)
)
FROM analytics.v_contents v
WHERE v.posted_at IS NOT NULL" ' > /tmp/timely_retro.tsv
wc -l /tmp/timely_retro.tsv   # v_contents 행수와 일치 확인
```

- [ ] **Step 4-2 (배포 후): 적재 + dry-run 카운트**

```bash
docker exec -i deploy-postgres-1 sh -c 'psql -U $POSTGRES_USER -d analysis' <<'SQL'
DROP TABLE IF EXISTS tmp_timely_retro;
CREATE TABLE tmp_timely_retro (short_code text PRIMARY KEY, timely boolean NOT NULL);
\copy tmp_timely_retro FROM '/dev/stdin'
SQL
# ↑ \copy는 stdin 리다이렉트가 얽히므로 실제로는 두 단계로:
docker exec deploy-postgres-1 sh -c 'psql -U $POSTGRES_USER -d analysis -c "DROP TABLE IF EXISTS tmp_timely_retro; CREATE TABLE tmp_timely_retro (short_code text PRIMARY KEY, timely boolean NOT NULL);"'
docker exec -i deploy-postgres-1 sh -c 'psql -U $POSTGRES_USER -d analysis -c "\copy tmp_timely_retro FROM STDIN"' < /tmp/timely_retro.tsv

docker exec deploy-postgres-1 sh -c 'psql -U $POSTGRES_USER -d analysis' <<'SQL'
-- dry-run: 플립 클래스별 건수 (실행 전 보고용)
SELECT 'a_승격: 캘린더timely인데 비timely 마킹' AS cls, count(*) FROM content_analyses a
  JOIN tmp_timely_retro t USING (short_code)
  WHERE t.timely AND a.metric_timeliness IS DISTINCT FROM 'timely'
UNION ALL
SELECT 'b_강등: timely 마킹인데 캘린더 not-timely', count(*) FROM content_analyses a
  JOIN tmp_timely_retro t USING (short_code)
  WHERE NOT t.timely AND a.metric_timeliness = 'timely'
UNION ALL
SELECT 'c_추출누락(변경 안 함·보고만)', count(*) FROM content_analyses a
  WHERE NOT EXISTS (SELECT 1 FROM tmp_timely_retro t WHERE t.short_code = a.short_code)
UNION ALL
SELECT 'r_랭킹모수(전)', count(*) FROM content_analyses
  WHERE is_beauty AND (metric_timeliness = 'timely' OR metric_timeliness IS NULL);
SQL
```

기대 규모(07-28 오전 실측 기준, 실행일까지의 배치로 변동): a ≈ 2.8천, b ≈ 4.1천+, c ≈ 배포 후 잡이 뷰 기준으로 돈 만큼 감소. **여기서 멈추고 카운트를 사용자에게 보고 → 승인 후 Step 4-3.**

- [ ] **Step 4-3 (승인 후): 정정 트랜잭션 + 검증 + 정리**

```bash
docker exec deploy-postgres-1 sh -c 'psql -U $POSTGRES_USER -d analysis' <<'SQL'
BEGIN;
UPDATE content_analyses a SET metric_timeliness = 'timely'
  FROM tmp_timely_retro t
  WHERE t.short_code = a.short_code AND t.timely
    AND a.metric_timeliness IS DISTINCT FROM 'timely';
UPDATE content_analyses a SET metric_timeliness = 'late_backfill'
  FROM tmp_timely_retro t
  WHERE t.short_code = a.short_code AND NOT t.timely
    AND a.metric_timeliness = 'timely';
-- 항등식: 추출에 있는 모든 분석행은 마킹 == 캘린더 판정이어야 한다 (0이어야 커밋)
SELECT count(*) AS mismatch FROM content_analyses a
  JOIN tmp_timely_retro t USING (short_code)
  WHERE (t.timely AND a.metric_timeliness <> 'timely')
     OR (NOT t.timely AND a.metric_timeliness = 'timely');
SELECT 'r_랭킹모수(후)', count(*) FROM content_analyses
  WHERE is_beauty AND (metric_timeliness = 'timely' OR metric_timeliness IS NULL);
COMMIT;
DROP TABLE tmp_timely_retro;
SQL
```

mismatch가 0이 아니면 `COMMIT` 대신 `ROLLBACK` 하고 원인 조사. 추출 누락(c 클래스)은 의도적으로 건드리지 않는다 — v_contents에서 빠진 행은 캘린더 판정 불가라 기존 마킹 보존.

- [ ] **Step 4-4 (실행 후): 결과 보고**

플립 실측치·랭킹 모수 전/후를 사용자에게 보고하고, 본 계획 문서 상태 헤더를 ✅로 갱신 후 `plans/archive/`로 이동.

---

### Task 5: 문서 갱신

**Files:**
- Modify: `ARCHITECTURE.md` (§5 트랙 표, §7 결정 기록)

- [ ] **Step 5-1: §7 결정 기록 추가**

기존 §7 관용(날짜 · 결정 · 근거 한 줄)을 따라 추가:

```
- 07-28 · timely 판정은 캘린더일(KST)로 단일화(정본=04 후보 뷰), ContentAnalysisJob의
  간격식 판정 제거 + 기존 마킹 양방향 소급 — 이원화로 일 수백 건이 late_backfill로 새어
  랭킹에서 영구 제외되던 표류 해소. 소급으로 랭킹 모수 순감소(~-2.5천, 뷰티 필터 전)는
  캘린더 정의에 맞춘 정직한 결과로 수용(PO 07-28).
```

- [ ] **Step 5-2: §5 트랙 표 갱신**

§5에 본 트랙 행 추가(관용에 맞춰): 트랙명 `timely 캘린더일 정합`, 상태는 PR 오픈 시점 기준(예: `PR #NNN 리뷰 대기·소급 런북 배포 후 실행 대기`).

- [ ] **Step 5-3: Commit**

```bash
git add ARCHITECTURE.md docs/superpowers/plans/2026-07-28-timely-calendar-alignment.md
git commit -m "docs: ARCHITECTURE §5·§7 timely 캘린더일 정합 반영 + 구현 계획"
```

---

### Task 6: 전체 검증 + PR

- [ ] **Step 6-1: 전체 테스트**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL (crawler·analytics·was 전 모듈. 로컬 Docker(colima) 필요)

- [ ] **Step 6-2: push + PR 생성**

```bash
git push -u origin feat/timely-calendar-alignment
gh pr create --base develop --title "feat(analytics): timely 판정 캘린더일 정합 + 소급 런북" --body "$(cat <<'EOF'
## 요약
- timely(제때 크롤) 판정 이원화 해소: ContentAnalysisJob의 간격식 판정을 제거하고 raw 후보 뷰(analytics.v_analysis_candidates, 캘린더일 KST)의 timely 컬럼을 정본으로 소비
- 성숙·최근12 윈도우·창닫힘 게이트는 뷰 소관으로 단일화 — 해당 Java 게이트 테스트는 SQL 하니스 04로 이관(recent-window=0 킬스위치 케이스 신규 추가)
- 라이브 뷰 ↔ 미러 간극 가드: 미러 부재 후보는 스킵(실패 카운트 오염 방지)
- 기존 마킹 양방향 소급 런북 포함(plans 문서 §Task 4) — 배포 후 dry-run → 승인 → COMMIT

## 배경
간격식(캡처가 업로드+3~4일 '시간 간격')과 캘린더일(07-20 뷰 정정) 판정이 갈라져, 낮 업로드가 D+3 새벽 크롤에 잡혀도 잡 눈엔 not-timely(간격 2.x일) → 일 ~350–770건이 late_backfill로 새어 랭킹에서 영구 제외되고, 대시보드(뷰 기준) timely 잔여가 배치 후에도 0이 안 되던 표류(07-28 실측 1,262건).

스펙: docs/superpowers/specs/2026-07-28-timely-calendar-alignment-design.md

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

- [ ] **Step 6-3: CI 통과 확인**

Run: `gh pr checks --watch`
Expected: `test`·`sql-harness` 잡 모두 pass

---

## Self-Review 체크 결과

- 스펙 §4(잡 변경) → Task 1·2 / §5(소급) → Task 4 / §6(대시보드 확인) → Task 2-6·구현 중 확인 / §7(테스트) → Task 1·3 / §8(문서) → Task 5. 커버리지 공백 없음.
- 소급 ⓑ는 스펙의 "뷰 이탈 861 포함"보다 보수적으로 좁혔다(추출=전 v_contents 기준, v_contents에도 없는 행은 판정 불가라 보존) — 스펙 §5의 "전 콘텐츠에 게이트 없이 적용" 원칙과 일치하며, 861건 대부분은 v_contents엔 있으므로 실제로 커버됨.
