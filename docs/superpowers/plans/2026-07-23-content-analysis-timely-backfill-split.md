# ContentAnalysisJob timely/late_backfill 분리 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `ContentAnalysisJob`의 후보 선정을 timely 전용(`run()`)과 late_backfill 전용(`runLateBackfill()`)
쿼리로 분리해, 늦크롤 백필 후보가 몰려도 매일 갱신돼야 할 timely 분석이 밀리지 않게 한다.

**Architecture:** 클래스는 하나로 유지하고 진입점만 둘로 나눈다. 두 SQL은 `NOT timely`로 상호
배타적이라 동시 실행돼도 같은 콘텐츠를 두 잡이 집지 않는다. LIMIT은 완전히 제거하고(실질 상한은
LLM 429 quota + 기존 carryover 로직), 두 진입점은 각자 `JobName`·cron·admin 트리거를 갖는다.

**Tech Stack:** Java 21, Spring Boot 4.1 (analytics 모듈), JdbcTemplate, JUnit 5 + Testcontainers
(PostgreSQL), Mockito, AssertJ.

**참고 스펙:** [docs/superpowers/specs/2026-07-23-content-analysis-timely-backfill-split-design.md](../specs/2026-07-23-content-analysis-timely-backfill-split-design.md)

---

## Task 1: JobName에 LATE_BACKFILL_ANALYZE 추가

**Files:**
- Modify: `analytics/src/main/java/com/celfit/analytics/admin/JobName.java`
- Test: `analytics/src/test/java/com/celfit/analytics/admin/JobNameTest.java`

- [ ] **Step 1: 테스트에 새 slug 검증 추가**

`analytics/src/test/java/com/celfit/analytics/admin/JobNameTest.java`의
`slug은_소문자_하이픈()`을 다음으로 교체:

```java
	@Test
	void slug은_소문자_하이픈() {
		assertThat(JobName.MIRROR.slug()).isEqualTo("mirror");
		assertThat(JobName.ACCOUNT_ANALYZE.slug()).isEqualTo("account-analyze");
		assertThat(JobName.LATE_BACKFILL_ANALYZE.slug()).isEqualTo("late-backfill-analyze");
	}
```

- [ ] **Step 2: 테스트 실행 — 컴파일 실패 확인**

Run: `./gradlew :analytics:test --tests "com.celfit.analytics.admin.JobNameTest"`
Expected: FAIL (컴파일 에러 — `JobName.LATE_BACKFILL_ANALYZE`가 아직 없음)

- [ ] **Step 3: enum 값 추가**

`analytics/src/main/java/com/celfit/analytics/admin/JobName.java`에서 enum 상수 목록을 교체:

```java
public enum JobName {
	MIRROR("미러 — 분석 뷰 → analysis DB"),
	CLASSIFY("댓글 분류 (LLM)"),
	ANALYZE("콘텐츠 분석 (LLM)"),
	ACCOUNT_ANALYZE("계정 카피 (LLM)"),
	ARCHIVE("이미지 아카이브 — CDN→오브젝트 스토리지"),
	LATE_BACKFILL_ANALYZE("늦크롤 백필 분석 (LLM)");
```

(나머지 메서드는 그대로 — `slug()`는 `_` → `-` 치환을 그대로 재사용해 `late-backfill-analyze`를 만든다.)

- [ ] **Step 4: 테스트 실행 — 통과 확인**

Run: `./gradlew :analytics:test --tests "com.celfit.analytics.admin.JobNameTest"`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add analytics/src/main/java/com/celfit/analytics/admin/JobName.java \
        analytics/src/test/java/com/celfit/analytics/admin/JobNameTest.java
git commit -m "feat(analytics): JobName에 LATE_BACKFILL_ANALYZE 추가"
```

---

## Task 2: ContentAnalysisJob — 기준선 로딩을 loadBaselines()로 추출

순수 리팩터(동작 불변) — timely/late_backfill 두 진입점이 곧 이 로딩을 공유해야 하므로 먼저 뗀다.

**Files:**
- Modify: `analytics/src/main/java/com/celfit/analytics/analyze/ContentAnalysisJob.java:61-95`
- Test: `analytics/src/test/java/com/celfit/analytics/analyze/ContentAnalysisJobTest.java` (변경 없음 — 회귀 확인용)

- [ ] **Step 1: 리팩터 전 테스트가 전부 통과하는지 기준선 확인**

Run: `./gradlew :analytics:test --tests "com.celfit.analytics.analyze.ContentAnalysisJobTest"`
Expected: PASS (전부 green — 이게 리팩터 전 기준선)

- [ ] **Step 2: `run()` 61~95행(기준선 두 종 로딩 블록)을 `loadBaselines()` 메서드로 추출**

`ContentAnalysisJob.java`에서 `run()` 메서드 시작의 기준선 로딩 부분(61~94행, `public JobResult run() {`
바로 아래부터 `Map<String, Boolean> eligible = new LinkedHashMap<>();` 직전까지)을 잘라내
새 private 메서드로 만들고, `run()`은 그 결과를 받아 쓰도록 바꾼다.

클래스 필드 목록 바로 아래, `public ContentAnalysisJob(...)` 생성자 다음에 추가:

```java
	/** raw v_analysis_account_baseline·v_analysis_baseline 1회 로딩 결과 — run()·runLateBackfill() 공유. */
	private record Baselines(Map<String, Baseline> accountBaseline, Map<String, Baseline> withBaseline) {}

	// 기준선 두 종을 통째로 로드한다 — 뷰 평가가 운영 실측 분 단위(07-19, 27k 기준 4.5분)라
	// 건당 조회를 반복하면 배치가 뷰 스캔에 잠긴다. 1회 평가 후 메모리 맵 조회로 대체.
	// PG 타입이 numeric·bigint·smallint로 섞여 있어 전부 BigDecimal로 읽어 변환 (기존 관용구).
	private Baselines loadBaselines() {
		// ① 계정 평균(account_handle 키) — 최근창 밖 후보에 붙일 앵커. rank는 계정 단위가 아니라 null.
		Map<String, Baseline> accountBaseline = new LinkedHashMap<>();
		raw.query("""
				SELECT account_handle, recent_reels_avg_views, recent_reels_count,
				       recent_contents_count, recent12_avg_engagement_rate,
				       recent12_avg_like_count, recent12_avg_comment_count,
				       category_top_percentile, category_avg_views, category_sample_size
				FROM analytics.v_analysis_account_baseline""",
				rs -> {
					accountBaseline.put(rs.getString(1), new Baseline(
							longOf(rs.getBigDecimal(2)), null, intOf(rs.getBigDecimal(3)),
							intOf(rs.getBigDecimal(4)), rs.getBigDecimal(5),
							longOf(rs.getBigDecimal(6)), longOf(rs.getBigDecimal(7)),
							intOf(rs.getBigDecimal(8)), longOf(rs.getBigDecimal(9)), longOf(rs.getBigDecimal(10))));
				});
		// ② 콘텐츠 키 기준선(최근창 안 게시물만, rank 포함) — 있으면 계정 평균보다 우선.
		Map<String, Baseline> withBaseline = new LinkedHashMap<>();
		raw.query("""
				SELECT short_code, recent_reels_avg_views, rank_in_recent_reels, recent_reels_count,
				       recent_contents_count, recent12_avg_engagement_rate,
				       recent12_avg_like_count, recent12_avg_comment_count,
				       category_top_percentile, category_avg_views, category_sample_size
				FROM analytics.v_analysis_baseline""",
				rs -> {
					withBaseline.put(rs.getString(1), new Baseline(
							longOf(rs.getBigDecimal(2)), intOf(rs.getBigDecimal(3)), intOf(rs.getBigDecimal(4)),
							intOf(rs.getBigDecimal(5)), rs.getBigDecimal(6),
							longOf(rs.getBigDecimal(7)), longOf(rs.getBigDecimal(8)),
							intOf(rs.getBigDecimal(9)), longOf(rs.getBigDecimal(10)), longOf(rs.getBigDecimal(11))));
				});
		return new Baselines(accountBaseline, withBaseline);
	}
```

`run()` 본문에서 잘라낸 부분(원래 66~94행)을 다음 한 줄로 교체:

```java
		Baselines baselines = loadBaselines();
```

그리고 `run()` 안에서 이후 `withBaseline`·`accountBaseline`을 참조하던 자리(`analyzeOne(...)` 호출부,
141행 근방)를 `baselines.withBaseline()`·`baselines.accountBaseline()`으로 바꾼다:

```java
				analyzeOne(shortCode, model, baselines.withBaseline(), baselines.accountBaseline(), eligible.get(shortCode));
```

- [ ] **Step 3: 컴파일 + 테스트 실행 — 여전히 전부 통과 확인**

Run: `./gradlew :analytics:test --tests "com.celfit.analytics.analyze.ContentAnalysisJobTest"`
Expected: PASS (동작 무변경 — 순수 추출 리팩터)

- [ ] **Step 4: 커밋**

```bash
git add analytics/src/main/java/com/celfit/analytics/analyze/ContentAnalysisJob.java
git commit -m "refactor(analytics): ContentAnalysisJob 기준선 로딩을 loadBaselines()로 추출"
```

---

## Task 3: ContentAnalysisJob 생성자에 backfillReporter 추가 + 배선

두 진입점(timely/late_backfill)이 어드민에 각자 진행률을 보고하려면 reporter가 둘 필요하다. 이
단계에서는 아직 실제로 late_backfill 진입점을 만들지 않고, 생성자 시그니처와 호출부만 맞춘다
(동작은 아직 무변경).

**Files:**
- Modify: `analytics/src/main/java/com/celfit/analytics/analyze/ContentAnalysisJob.java:26-52`
- Modify: `analytics/src/main/java/com/celfit/analytics/config/JobConfig.java:72-81`
- Modify: `analytics/src/test/java/com/celfit/analytics/analyze/ContentAnalysisJobTest.java:60-67,146-147,573-574`

- [ ] **Step 1: 필드·생성자에 backfillReporter 추가**

`ContentAnalysisJob.java`에서 필드 선언 블록의

```java
	private final ProgressReporter reporter;
```

을

```java
	private final ProgressReporter reporter;
	private final ProgressReporter backfillReporter; // runLateBackfill() 진행률 — run()의 reporter와 별도 JobName
```

으로 바꾸고, 생성자를

```java
	public ContentAnalysisJob(JdbcTemplate rawJdbcTemplate, DataSource analysisDataSource,
			ContentInsightPort insight, AnalyticsSettings settings,
			boolean thumbnailEnabled, Predicate<String> thumbnailAlive, ProgressReporter reporter) {
		this.raw = rawJdbcTemplate;
		this.analysis = new JdbcTemplate(analysisDataSource);
		this.insight = insight;
		this.settings = settings;
		this.thumbnailEnabled = thumbnailEnabled;
		this.thumbnailAlive = thumbnailAlive;
		this.reporter = reporter;
	}
```

에서

```java
	public ContentAnalysisJob(JdbcTemplate rawJdbcTemplate, DataSource analysisDataSource,
			ContentInsightPort insight, AnalyticsSettings settings,
			boolean thumbnailEnabled, Predicate<String> thumbnailAlive,
			ProgressReporter reporter, ProgressReporter backfillReporter) {
		this.raw = rawJdbcTemplate;
		this.analysis = new JdbcTemplate(analysisDataSource);
		this.insight = insight;
		this.settings = settings;
		this.thumbnailEnabled = thumbnailEnabled;
		this.thumbnailAlive = thumbnailAlive;
		this.reporter = reporter;
		this.backfillReporter = backfillReporter;
	}
```

로 바꾼다.

- [ ] **Step 2: JobConfig 배선 갱신**

`JobConfig.java`의 `contentAnalysisJob` 빈(72~81행)을:

```java
	@Bean
	@Lazy
	@ConditionalOnExpression("${analytics.analyze-on-startup:false} or ${analytics.admin-enabled:false}")
	public ContentAnalysisJob contentAnalysisJob(JdbcTemplate rawJdbcTemplate,
			@Qualifier("analysisDataSource") DataSource analysisDataSource,
			ContentInsightPort insight, AnalyticsSettings settings,
			// vlm-enabled = 썸네일 첨부 게이트 (기본 off — 캡션 기반 5종은 항상 산출)
			@Value("${analytics.vlm-enabled:false}") boolean thumbnailEnabled,
			ObjectProvider<JobProgressRegistry> progressRegistry) {
		JobProgressRegistry registry = progressRegistry.getIfAvailable();
		ProgressReporter reporter = registry != null ? registry.reporter(JobName.ANALYZE) : ProgressReporter.NOOP;
		ProgressReporter backfillReporter = registry != null
				? registry.reporter(JobName.LATE_BACKFILL_ANALYZE) : ProgressReporter.NOOP;
		return new ContentAnalysisJob(rawJdbcTemplate, analysisDataSource, insight,
				settings, thumbnailEnabled, headPrecheck(), reporter, backfillReporter);
	}
```

로 교체한다.

- [ ] **Step 3: 테스트 파일의 생성자 호출 3곳 갱신**

`ContentAnalysisJobTest.java`에서 `new ContentAnalysisJob(` 호출이 있는 3곳 모두 마지막 인자로
`ProgressReporter.NOOP`(또는 해당 테스트가 쓰는 reporter 변수)을 하나 더 추가한다.

64~67행:

```java
	void rewireJob(ContentInsightPort port, boolean thumbnailEnabled, java.util.function.Predicate<String> thumbnailAlive) {
		job = new ContentAnalysisJob(db, ds, port, new AnalyticsSettings(db),
				thumbnailEnabled, thumbnailAlive, ProgressReporter.NOOP, ProgressReporter.NOOP);
	}
```

146~147행:

```java
		job = new ContentAnalysisJob(db, ds, fakeInsightPort(), new AnalyticsSettings(db),
				false, url -> true, ProgressReporter.NOOP, ProgressReporter.NOOP);
```

573~574행:

```java
		job = new ContentAnalysisJob(db, ds, fakeInsightPort(), new AnalyticsSettings(db),
				false, url -> true, reporter, ProgressReporter.NOOP);
```

- [ ] **Step 4: 컴파일 + 테스트 실행 — 전부 통과 확인**

Run: `./gradlew :analytics:test --tests "com.celfit.analytics.analyze.ContentAnalysisJobTest"`
Expected: PASS (여전히 동작 무변경 — 생성자 시그니처만 확장)

Run: `./gradlew :analytics:compileJava`
Expected: BUILD SUCCESSFUL (JobConfig도 함께 컴파일 확인)

- [ ] **Step 5: 커밋**

```bash
git add analytics/src/main/java/com/celfit/analytics/analyze/ContentAnalysisJob.java \
        analytics/src/main/java/com/celfit/analytics/config/JobConfig.java \
        analytics/src/test/java/com/celfit/analytics/analyze/ContentAnalysisJobTest.java
git commit -m "refactor(analytics): ContentAnalysisJob에 backfillReporter 배선 추가"
```

---

## Task 4: SQL 후보 선정 분리 (timely/late_backfill), LIMIT 제거

핵심 동작 변경. 테스트를 먼저 새 동작에 맞게 고치고(빨강), 그다음 구현한다(초록).

**Files:**
- Modify: `analytics/src/test/java/com/celfit/analytics/analyze/ContentAnalysisJobTest.java`
- Modify: `analytics/src/main/java/com/celfit/analytics/analyze/ContentAnalysisJob.java:19-172`

- [ ] **Step 1: `분석_대상은_수집_최신순이다()` 수정 — LIMIT 대신 호출 순서로 검증**

기존(321~331행):

```java
	@Test
	void 분석_대상은_수집_최신순이다() {
		// 썸네일 서명 URL이 살아있을 때 VLM을 시도하기 위해 최신 수집분부터 (short_code 순이면 post_a 먼저)
		db.update("INSERT INTO app_setting(key, value) VALUES ('analytics.analyze-batch-limit', '1')");

		int processed = job.run().processed();

		assertEquals(1, processed);
		assertEquals(1L, db.queryForObject(
				"SELECT count(*) FROM content_analyses WHERE short_code = 'post_b'", Long.class)); // captured_at 최신
	}
```

를 다음으로 교체:

```java
	@Test
	void 분석_대상은_수집_최신순이다() {
		// 썸네일 서명 URL이 살아있을 때 VLM을 먼저 시도하기 위해 최신 수집분부터 처리한다.
		// LIMIT을 없앴으므로(전량 처리) 순서는 insightCalls 호출 순서로 검증한다.
		int processed = job.run().processed();

		assertEquals(2, processed);
		assertEquals("post_b", insightCalls.get(0).shortCode()); // captured_at 최신
		assertEquals("post_a", insightCalls.get(1).shortCode());
	}
```

- [ ] **Step 2: `진행률을_보고한다()` 수정 — LIMIT 대신 숙성 가드로 대상 1건 고정**

기존(569~580행, Task 3에서 이미 NOOP 인자가 추가된 상태):

```java
	@Test
	void 진행률을_보고한다() {
		// 대상 1건으로 고정 — 최초 보고(대상 확정 직후)와 마지막 보고(처리 완료 직후)만 검증
		db.update("INSERT INTO app_setting(key, value) VALUES ('analytics.analyze-batch-limit', '1')");
		List<int[]> reports = new ArrayList<>();
		ProgressReporter reporter = (p, f, t) -> reports.add(new int[]{p, f, t});
		job = new ContentAnalysisJob(db, ds, fakeInsightPort(), new AnalyticsSettings(db),
				false, url -> true, reporter, ProgressReporter.NOOP);

		job.run();

		assertThat(reports.getFirst()).containsExactly(0, 0, 1);
		assertThat(reports.getLast()).containsExactly(1, 0, 1);
	}
```

를 다음으로 교체:

```java
	@Test
	void 진행률을_보고한다() {
		// 대상 1건으로 고정 — 최초 보고(대상 확정 직후)와 마지막 보고(처리 완료 직후)만 검증.
		// LIMIT이 없어졌으므로 post_b를 숙성 가드 미달로 만들어 대상에서 빼는 방식으로 1건을 고정한다.
		db.update("UPDATE contents SET posted_at = now() - interval '1 day' WHERE short_code = 'post_b'");
		List<int[]> reports = new ArrayList<>();
		ProgressReporter reporter = (p, f, t) -> reports.add(new int[]{p, f, t});
		job = new ContentAnalysisJob(db, ds, fakeInsightPort(), new AnalyticsSettings(db),
				false, url -> true, reporter, ProgressReporter.NOOP);

		job.run();

		assertThat(reports.getFirst()).containsExactly(0, 0, 1);
		assertThat(reports.getLast()).containsExactly(1, 0, 1);
	}
```

- [ ] **Step 3: `늦크롤이라도_최근_윈도우_안이면_분석하고_late_backfill로_마킹한다()` 수정 — run()/runLateBackfill() 각각 호출**

기존(455~471행):

```java
	@Test
	void 늦크롤이라도_최근_윈도우_안이면_분석하고_late_backfill로_마킹한다() {
		// 07-20 PO 결정(스펙 2026-07-20-vertex-migration-recent12-backfill-design.md): 늦크롤(제때
		// 크롤 실패)이라도 계정의 최근 N개(기본 12) 윈도우 안이면 일상 분석 대상에 포함하고
		// V33 metric_timeliness를 late_backfill로 마킹한다. acct1은 총 3건뿐이라 기본 윈도우(12) 안.
		db.update("""
				UPDATE contents SET posted_at = now() - interval '20 days',
				  metric_captured_at = now() - interval '9 days' WHERE short_code = 'post_a'""");

		int processed = job.run().processed();

		assertEquals(2, processed); // post_a(늦크롤이지만 윈도우 안), post_b
		assertEquals(1L, db.queryForObject(
				"SELECT count(*) FROM content_analyses WHERE short_code = 'post_a'", Long.class));
		assertEquals("late_backfill", db.queryForObject(
				"SELECT metric_timeliness FROM content_analyses WHERE short_code = 'post_a'", String.class));
	}
```

를 다음으로 교체(잡이 둘로 나뉘었으므로 각각 호출):

```java
	@Test
	void 늦크롤이라도_최근_윈도우_안이면_runLateBackfill이_분석하고_late_backfill로_마킹한다() {
		// 07-20 PO 결정(스펙 2026-07-20-vertex-migration-recent12-backfill-design.md): 늦크롤(제때
		// 크롤 실패)이라도 계정의 최근 N개(기본 12) 윈도우 안이면 late_backfill 잡의 대상에 포함하고
		// V33 metric_timeliness를 late_backfill로 마킹한다. acct1은 총 3건뿐이라 기본 윈도우(12) 안.
		// timely·backfill 쿼리는 상호 배타적이므로(2026-07-23 설계) post_a는 runLateBackfill()에서만,
		// post_b는 run()에서만 나온다.
		db.update("""
				UPDATE contents SET posted_at = now() - interval '20 days',
				  metric_captured_at = now() - interval '9 days' WHERE short_code = 'post_a'""");

		int timelyProcessed = job.run().processed();
		int backfillProcessed = job.runLateBackfill().processed();

		assertEquals(1, timelyProcessed); // post_b만
		assertEquals(1, backfillProcessed); // post_a(늦크롤이지만 윈도우 안)
		assertEquals(1L, db.queryForObject(
				"SELECT count(*) FROM content_analyses WHERE short_code = 'post_a'", Long.class));
		assertEquals("late_backfill", db.queryForObject(
				"SELECT metric_timeliness FROM content_analyses WHERE short_code = 'post_a'", String.class));
	}
```

- [ ] **Step 4: `늦크롤이면서_제때창이_아직_열려있으면_윈도우_안이어도_제외된다()` 수정 — runLateBackfill()로 게이트 검증**

기존(499~517행)은 `job.run()`만 호출하는데, 검증하려는 "창 닫힘 게이트"는 분리 후 late_backfill
쿼리에만 남는 로직이라 `run()` 호출로는 더 이상 이 게이트를 실질적으로 통과하지 않는다(post_a가
애초 timely가 아니라 run()엔 안 잡히므로). `runLateBackfill()`로 바꿔 실제로 게이트를 태운다.

기존:

```java
	@Test
	void 늦크롤이면서_제때창이_아직_열려있으면_윈도우_안이어도_제외된다() {
		// 최종 통합 리뷰 I-1: 제때창(posted_at + pin(3) + slack(2) = 기본 5일)이 아직 안 닫힌
		// 콘텐츠를 윈도우 경로로 조기 분석하면, 나중에 진짜 timely 스냅샷이 들어와도
		// content_analyses가 불변이라 late_backfill로 영구 오분류된다. 04 뷰의 "제때창이 완전히
		// 지난 날만 후보" 성숙 철학과 정렬하기 위해 윈도우 분기에도 창 닫힘 게이트를 건다.
		// post_a: 숙성(3일)은 지났지만(4일 전 게시) pin+slack(5일)은 아직 안 지났다 — 창이
		// 열려 있는 상태. 지표도 미성숙(게시 +0.5일 캡처)이라 timely=false. acct1은 3건뿐이라
		// 기본 윈도우(12)에서 rank상으로는 포함 대상이지만, 창이 열려 있으니 제외돼야 한다.
		db.update("""
				UPDATE contents SET posted_at = now() - interval '4 days',
				  metric_captured_at = now() - interval '3 days 12 hours' WHERE short_code = 'post_a'""");

		int processed = job.run().processed();

		assertEquals(1, processed); // post_b만 (post_a는 창이 아직 열려 있어 윈도우 경로로도 제외)
		assertEquals(0L, db.queryForObject(
				"SELECT count(*) FROM content_analyses WHERE short_code = 'post_a'", Long.class));
	}
```

를 다음으로 교체:

```java
	@Test
	void 늦크롤이면서_제때창이_아직_열려있으면_runLateBackfill에서도_제외된다() {
		// 최종 통합 리뷰 I-1: 제때창(posted_at + pin(3) + slack(2) = 기본 5일)이 아직 안 닫힌
		// 콘텐츠를 윈도우 경로로 조기 분석하면, 나중에 진짜 timely 스냅샷이 들어와도
		// content_analyses가 불변이라 late_backfill로 영구 오분류된다. 04 뷰의 "제때창이 완전히
		// 지난 날만 후보" 성숙 철학과 정렬하기 위해 late_backfill 쿼리에도 창 닫힘 게이트를 건다.
		// post_a: 숙성(3일)은 지났지만(4일 전 게시) pin+slack(5일)은 아직 안 지났다 — 창이
		// 열려 있는 상태. 지표도 미성숙(게시 +0.5일 캡처)이라 timely=false. acct1은 3건뿐이라
		// 기본 윈도우(12)에서 rank상으로는 포함 대상이지만, 창이 열려 있으니 제외돼야 한다.
		// post_a가 timely가 아니므로 run()으로는 이 게이트를 태우지 못한다 — runLateBackfill()로 검증.
		db.update("""
				UPDATE contents SET posted_at = now() - interval '4 days',
				  metric_captured_at = now() - interval '3 days 12 hours' WHERE short_code = 'post_a'""");

		int backfillProcessed = job.runLateBackfill().processed();

		assertEquals(0, backfillProcessed); // post_a는 창 안 닫힘, post_b는 애초 timely라 백필 대상 아님
		assertEquals(0L, db.queryForObject(
				"SELECT count(*) FROM content_analyses WHERE short_code = 'post_a'", Long.class));
	}
```

- [ ] **Step 5: 상호 배타성 회귀 테스트 추가**

`제때_크롤분은_timely로_마킹한다()` 테스트(473~481행) 바로 뒤에 새 테스트를 추가한다:

```java
	@Test
	void timely이면서_최근_윈도우_안인_콘텐츠는_runLateBackfill_쿼리에서_제외된다() {
		// 설계(2026-07-23): backfill 쿼리는 NOT timely를 명시한다. setUp 기본 픽스처의 post_a·post_b는
		// 둘 다 timely면서 동시에 최근 윈도우 안(acct1 총 3건 < 기본 윈도우 12)이지만, 이제 timely
		// 쪽이 먼저 가져가므로 runLateBackfill()에는 잡히지 않아야 한다 — 두 쿼리의 short_code 집합이
		// 항상 서로소여야 content_analyses INSERT 경합이 안 생긴다.
		int backfillProcessed = job.runLateBackfill().processed();

		assertEquals(0, backfillProcessed);
		assertEquals(0L, db.queryForObject("SELECT count(*) FROM content_analyses", Long.class));
	}
```

- [ ] **Step 6: 테스트 실행 — 실패(컴파일 에러) 확인**

Run: `./gradlew :analytics:test --tests "com.celfit.analytics.analyze.ContentAnalysisJobTest"`
Expected: FAIL (컴파일 에러 — `job.runLateBackfill()`가 아직 존재하지 않음)

- [ ] **Step 7: ContentAnalysisJob 구현 — 쿼리 분리 + runLateBackfill() 추가 + LIMIT 제거**

`ContentAnalysisJob.java`의 클래스 상단 주석(19~25행)을 교체:

```java
/**
 * 콘텐츠 분석 배치 (스펙 §6). 분석 시점 고정·불변 — INSERT만, 재분석 없음.
 * 대상: 미분석 AND (댓글 없음 OR 분류 완료) AND 게시 후 N일 경과(기본 3 — B3 숙성 가드).
 * timely(run())와 late_backfill(runLateBackfill())은 서로 다른 진입점 — 예산·스케줄이 별도라
 * 백필 후보가 몰려도 매일 갱신돼야 할 timely 분석이 밀리지 않는다(2026-07-23 설계, NOT timely로
 * 상호 배타적).
 * 속성 분석은 캡션 주·썸네일 보조 (2026-07-14 캡션 분류 스펙) — 썸네일 만료여도 캡션으로 5종 산출.
 * 콘텐츠 단위 실패 격리: 한 건 실패는 로그 후 계속 (B2 리뷰 반영).
 */
```

이어서 `EMPTY_BASELINE` 필드 선언 바로 아래에 두 SQL 상수를 추가:

```java
	// 제때 크롤 가드(07-19 정정, 판정식 07-20 보존): 고정 지표가 성숙(+pin일) 스냅샷이면서
	// +(pin+slack)일 안에 잡힌 것만 timely. posted_at·metric_captured_at NULL은 COALESCE로
	// timely=false로 자연 제외.
	private static final String TIMELY_SQL = """
			WITH base AS (
			  SELECT c.short_code, c.metric_captured_at,
			         COALESCE(c.metric_captured_at >= c.posted_at + make_interval(days => ?)
			              AND c.metric_captured_at < c.posted_at + make_interval(days => ?), false) AS timely
			  FROM contents c
			  WHERE NOT EXISTS (SELECT 1 FROM content_analyses a WHERE a.short_code = c.short_code)
			    AND (NOT EXISTS (SELECT 1 FROM content_comments m WHERE m.short_code = c.short_code)
			         OR EXISTS (SELECT 1 FROM comment_classifications k WHERE k.short_code = c.short_code))
			    AND c.posted_at <= now() - make_interval(days => ?)
			)
			SELECT short_code
			FROM base
			WHERE timely
			ORDER BY metric_captured_at DESC NULLS LAST, short_code""";

	// timely 가드를 못 채운 콘텐츠 중 계정별 최근 N개(recent-window) 윈도우 안인 것만 — 늦크롤 백필
	// (07-20 재도입). NOT timely로 timely 쿼리와 상호 배타적(같은 콘텐츠를 두 잡이 동시에 집지 않음).
	// 창 닫힘 게이트(posted_at + (pin+slack)일 <= now())는 윈도우 분기에만 건다 — 제때창이 아직 열려
	// 있는 콘텐츠를 조기에 late_backfill로 분석해버리면, 나중에 진짜 timely 스냅샷이 들어와도
	// content_analyses가 불변이라 영구 오분류된다.
	private static final String LATE_BACKFILL_SQL = """
			WITH base AS (
			  SELECT c.short_code, c.metric_captured_at,
			         COALESCE(c.metric_captured_at >= c.posted_at + make_interval(days => ?)
			              AND c.metric_captured_at < c.posted_at + make_interval(days => ?), false) AS timely
			  FROM contents c
			  WHERE NOT EXISTS (SELECT 1 FROM content_analyses a WHERE a.short_code = c.short_code)
			    AND (NOT EXISTS (SELECT 1 FROM content_comments m WHERE m.short_code = c.short_code)
			         OR EXISTS (SELECT 1 FROM comment_classifications k WHERE k.short_code = c.short_code))
			    AND c.posted_at <= now() - make_interval(days => ?)
			),
			ranked AS (
			  SELECT short_code, posted_at,
			         row_number() OVER (PARTITION BY account_handle
			             ORDER BY posted_at DESC, short_code DESC) AS rn
			  FROM contents
			)
			SELECT short_code
			FROM base
			WHERE NOT timely AND short_code IN (
			  SELECT short_code FROM ranked
			  WHERE rn <= ? AND posted_at <= now() - make_interval(days => ?)
			)
			ORDER BY metric_captured_at DESC NULLS LAST, short_code""";
```

`run()` 메서드 전체(Task 2 리팩터 이후 버전)를 다음으로 교체:

```java
	/**
	 * @return 잡 실행 결과 (처리·실패 건수, 일 한도 이월 여부)
	 *
	 * <p>timely 가드를 충족한 미분석 콘텐츠 전량(LIMIT 없음 — 실질 상한은 LLM 429 quota).
	 */
	public JobResult run() {
		int pinDays = settings.metricPinDays();
		int slackDays = settings.analyzeTimelySlackDays();
		return runQuery(TIMELY_SQL,
				new Object[] {pinDays, pinDays + slackDays, settings.analyzeMaturityDays()},
				true, reporter);
	}

	/**
	 * @return 잡 실행 결과 (처리·실패 건수, 일 한도 이월 여부)
	 *
	 * <p>timely 가드는 못 채웠지만 계정별 최근 N개 윈도우 안인 콘텐츠 전량(LIMIT 없음).
	 * run()과 상호 배타적 — 같은 short_code가 두 쿼리에 동시에 잡히지 않는다.
	 */
	public JobResult runLateBackfill() {
		int pinDays = settings.metricPinDays();
		int slackDays = settings.analyzeTimelySlackDays();
		return runQuery(LATE_BACKFILL_SQL,
				new Object[] {pinDays, pinDays + slackDays, settings.analyzeMaturityDays(),
						settings.recentWindow(), pinDays + slackDays},
				false, backfillReporter);
	}

	private JobResult runQuery(String sql, Object[] params, boolean timely, ProgressReporter progress) {
		Baselines baselines = loadBaselines();
		List<String> targets = new ArrayList<>();
		analysis.query(sql, rs -> targets.add(rs.getString(1)), params);
		String model = settings.activeLlmModel();
		int processed = 0;
		int failed = 0;
		boolean carriedOver = false;
		progress.report(0, 0, targets.size());
		for (String shortCode : targets) {
			try {
				analyzeOne(shortCode, model, baselines.withBaseline(), baselines.accountBaseline(), timely);
				processed++;
			} catch (com.celfit.analytics.llm.LlmQuotaExhaustedException e) {
				// 일 한도 소진 — 에러가 아닌 이월: 남은 대상은 다음 실행에서 자연 재대상 (07-18 확정)
				log.warn("LLM 일 한도 소진 — 배치 중단, 잔여 {}건 이월", targets.size() - processed - failed);
				carriedOver = true;
				break;
			} catch (Exception e) {
				failed++;
				log.error("analysis failed for {} — 다음 실행에서 재대상", shortCode, e);
			}
			progress.report(processed, failed, targets.size());
		}
		log.info("analysis complete ({} contents, {} failed)", processed, failed);
		return new JobResult(processed, failed, carriedOver);
	}
```

(그 아래 `loadBaselines()`·`analyzeOne()`·`longOf()`·`intOf()`는 Task 2에서 이미 만든 그대로 —
`analyzeOne`은 시그니처·본문 무변경.)

- [ ] **Step 8: 테스트 실행 — 전부 통과 확인**

Run: `./gradlew :analytics:test --tests "com.celfit.analytics.analyze.ContentAnalysisJobTest"`
Expected: PASS (전체 그린 — 기존 케이스 + 신규 상호 배타성 케이스 포함)

- [ ] **Step 9: 커밋**

```bash
git add analytics/src/main/java/com/celfit/analytics/analyze/ContentAnalysisJob.java \
        analytics/src/test/java/com/celfit/analytics/analyze/ContentAnalysisJobTest.java
git commit -m "feat(analytics): timely/late_backfill 후보 쿼리 분리, LIMIT 제거"
```

---

## Task 5: 스케줄·어드민 배선 (ScheduleRunner, AnalyticsJobService, AdminUiController)

**Files:**
- Modify: `analytics/src/main/java/com/celfit/analytics/admin/ScheduleRunner.java`
- Modify: `analytics/src/main/java/com/celfit/analytics/admin/AnalyticsJobService.java:101-126`
- Modify: `analytics/src/main/java/com/celfit/analytics/admin/AdminUiController.java:35-36`
- Modify: `analytics/src/main/resources/application.yml:19-21`
- Test: `analytics/src/test/java/com/celfit/analytics/admin/AnalyticsJobServiceTest.java`

- [ ] **Step 1: AnalyticsJobServiceTest에 새 잡 트리거 테스트 추가**

`트리거는_잡을_실행하고_ACCEPTED를_반환()` 테스트 바로 뒤에 추가:

```java
	@Test
	void late_backfill_잡을_트리거하면_runLateBackfill이_호출된다() {
		when(analyzeJob.runLateBackfill()).thenReturn(new JobResult(2, 0, false));
		var result = service().trigger(JobName.LATE_BACKFILL_ANALYZE, TriggerType.MANUAL);
		assertThat(result).isEqualTo(AnalyticsJobService.TriggerResult.ACCEPTED);
		var run = history.recent(1).getFirst();
		assertThat(run.job()).isEqualTo(JobName.LATE_BACKFILL_ANALYZE);
		assertThat(run.processed()).isEqualTo(2);
	}
```

- [ ] **Step 2: 테스트 실행 — 실패 확인**

Run: `./gradlew :analytics:test --tests "com.celfit.analytics.admin.AnalyticsJobServiceTest"`
Expected: FAIL (`AnalyticsJobService.run()`의 switch가 `LATE_BACKFILL_ANALYZE`를 모르는 case로 던짐 —
`MatchException`류)

- [ ] **Step 3: AnalyticsJobService switch에 case 추가**

`AnalyticsJobService.java`의 `run(JobName job)`(101~126행) 안 switch에서

```java
			case ANALYZE -> analyzeJob.getObject().run();
```

바로 아래에 추가:

```java
			case LATE_BACKFILL_ANALYZE -> analyzeJob.getObject().runLateBackfill();
```

- [ ] **Step 4: 테스트 실행 — 통과 확인**

Run: `./gradlew :analytics:test --tests "com.celfit.analytics.admin.AnalyticsJobServiceTest"`
Expected: PASS

- [ ] **Step 5: ScheduleRunner에 cron 메서드 추가**

`ScheduleRunner.java`의 `analyze()` 메서드 바로 뒤에 추가:

```java
	@Scheduled(cron = "${analytics.schedule.late-backfill-analyze-cron:-}")
	void lateBackfillAnalyze() {
		log.info("스케줄 late-backfill-analyze: {}",
				jobService.trigger(JobName.LATE_BACKFILL_ANALYZE, TriggerType.SCHEDULED));
	}
```

- [ ] **Step 6: AdminUiController 대시보드 목록에 추가**

`AdminUiController.java`의 `DASHBOARD_JOBS` 정의(35~36행)를:

```java
	private static final List<JobName> DASHBOARD_JOBS =
			List.of(JobName.MIRROR, JobName.ANALYZE, JobName.ACCOUNT_ANALYZE, JobName.ARCHIVE);
```

에서

```java
	private static final List<JobName> DASHBOARD_JOBS =
			List.of(JobName.MIRROR, JobName.ANALYZE, JobName.LATE_BACKFILL_ANALYZE,
					JobName.ACCOUNT_ANALYZE, JobName.ARCHIVE);
```

로 바꾼다. (`scopeLine`/`scopeSubLine`은 `default -> null`로 처리되므로 별도 수정 없이도 카드가
러닝 상태·이력 피드와 함께 뜬다.)

- [ ] **Step 7: application.yml에 cron 키 예시 주석 추가**

`application.yml`의

```yaml
  schedule:
    enabled: false             # 스케줄 트리거 골격 — 켜려면 true + 잡별 *-cron 지정 (admin-enabled 필요)
    # mirror-cron: "0 0 6 * * *"   # 켜는 날 예시. 미지정 잡은 "-"(비활성)
```

를

```yaml
  schedule:
    enabled: false             # 스케줄 트리거 골격 — 켜려면 true + 잡별 *-cron 지정 (admin-enabled 필요)
    # mirror-cron: "0 0 6 * * *"   # 켜는 날 예시. 미지정 잡은 "-"(비활성)
    # late-backfill-analyze-cron: "0 30 3 * * *"  # timely(analyze-cron) 직후 예시 — 별도 예산이라 독립 조정 가능
```

로 바꾼다.

- [ ] **Step 8: 전체 admin 패키지 테스트 실행**

Run: `./gradlew :analytics:test --tests "com.celfit.analytics.admin.*"`
Expected: PASS

- [ ] **Step 9: 커밋**

```bash
git add analytics/src/main/java/com/celfit/analytics/admin/ScheduleRunner.java \
        analytics/src/main/java/com/celfit/analytics/admin/AnalyticsJobService.java \
        analytics/src/main/java/com/celfit/analytics/admin/AdminUiController.java \
        analytics/src/main/resources/application.yml \
        analytics/src/test/java/com/celfit/analytics/admin/AnalyticsJobServiceTest.java
git commit -m "feat(analytics): late_backfill 잡 스케줄·어드민 배선"
```

---

## Task 6: 전체 검증

**Files:** 없음(실행만)

- [ ] **Step 1: analytics 모듈 전체 테스트**

Run: `./gradlew :analytics:test`
Expected: BUILD SUCCESSFUL, 전 테스트 PASS

- [ ] **Step 2: 전체 멀티모듈 빌드(회귀 확인 — crawler·was는 무변경이지만 계약 모듈 공유 확인 차)**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 스펙 문서 상태 헤더 갱신**

`docs/superpowers/specs/2026-07-23-content-analysis-timely-backfill-split-design.md` 1행을

```
> 상태: 🟢 활성 · ⏸ 계획 수립 중 (구현 전)
```

에서

```
> 상태: 🟢 활성 · ✅ 구현됨 (PR 대기 — develop 머지 전)
```

로 바꾼다.

- [ ] **Step 4: 커밋**

```bash
git add docs/superpowers/specs/2026-07-23-content-analysis-timely-backfill-split-design.md
git commit -m "docs: timely/late_backfill 분리 스펙 상태를 구현됨으로 갱신"
```

---

## 스코프 밖 (이번 계획에서 다루지 않음)

- 운영 cron 실값(`analytics.schedule.late-backfill-analyze-cron`) 설정 — 리포 밖 운영 env, 배포 후
  별도 조치.
- `feat/*` 브랜치 생성·PR — CLAUDE.md 컨벤션대로 이 계획 실행 전에 별도로 준비되어 있어야 한다
  (현재 세션 브랜치가 `develop`이 아니면 그대로 사용, `develop`이면 `feat/content-analysis-timely-backfill-split`
  같은 브랜치로 옮긴 뒤 진행).
- `GeminiBackfillRunner`(초기 백필 원샷) 변경 — 무관.
