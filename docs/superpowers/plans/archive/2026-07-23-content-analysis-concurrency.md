# ContentAnalysisJob 동시 처리(병렬화) Implementation Plan

> 상태: ✅ 구현됨 (PR #134 머지, 2026-07-24)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `ContentAnalysisJob.runQuery()`의 순차 처리 루프를 app_setting으로 조정 가능한
동시 처리(병렬)로 바꿔, 대량 백필 적체(운영 실측 26,167건)의 처리 시간을 줄인다.

**Architecture:** `Executors.newFixedThreadPool(concurrency)` + `invokeAll`로 대상 목록을
제출 순서(= 최신 수집순) 그대로 병렬 처리한다. 카운터는 `AtomicInteger`, 쿼타 소진은
`AtomicBoolean` 플래그로 조정 — 이미 진행 중인 호출은 완료시키고, 아직 시작 안 한 큐만
플래그를 보고 LLM 호출 없이 스킵한다.

**Tech Stack:** Java 21 (`ExecutorService`/`Callable`/`Atomic*`), Spring Boot 4.1 analytics 모듈,
JUnit 5 + Testcontainers.

**참고 스펙:** [docs/superpowers/specs/2026-07-23-content-analysis-concurrency-design.md](../../specs/archive/2026-07-23-content-analysis-concurrency-design.md)

> **실행 환경 참고:** 이 세션의 샌드박스에서 Testcontainers를 실제로 돌리려면
> `export TESTCONTAINERS_RYUK_DISABLED=true DOCKER_HOST=unix:///Users/woomin/.colima/default/docker.sock`
> 를 먼저 export하고 `--rerun-tasks`로 캐시를 우회해야 한다(이전 세션에서 검증됨). 이게 안 되는
> 환경이면 컴파일 성공 + 수동 코드 리뷰로 대체하고, 실행 결과는 "미실행"으로 정직하게 보고한다.

---

## Task 1: AnalyticsSettings에 analyzeConcurrency() 추가

**Files:**
- Modify: `analytics/src/main/java/com/celfit/analytics/config/AnalyticsSettings.java`
- Test: `analytics/src/test/java/com/celfit/analytics/analyze/ContentAnalysisJobTest.java` (설정 getter 테스트 — 이 파일이 기존 컨벤션상 `AnalyticsSettings`의 관련 getter들을 검증하는 곳, 예: `프로바이더_기본은_gemini고_app_setting으로_롤백된다()`)

- [ ] **Step 1: 실패하는 테스트 작성**

`analytics/src/test/java/com/celfit/analytics/analyze/ContentAnalysisJobTest.java`에서
`프로바이더_기본은_gemini고_app_setting으로_롤백된다()` 테스트(404~414행 근방) 바로 뒤에 추가:

```java
	@Test
	void 동시_처리_개수_기본값과_app_setting_오버라이드() {
		AnalyticsSettings settings = new AnalyticsSettings(db);
		assertEquals(8, settings.analyzeConcurrency());

		db.update("INSERT INTO app_setting(key, value) VALUES ('analytics.analyze-concurrency', '3')");
		assertEquals(3, settings.analyzeConcurrency());
	}
```

- [ ] **Step 2: 테스트 실행 — 컴파일 실패 확인**

Run: `./gradlew :analytics:compileTestJava`
Expected: FAIL (컴파일 에러 — `AnalyticsSettings.analyzeConcurrency()`가 아직 없음)

- [ ] **Step 3: AnalyticsSettings에 키·기본값·getter 추가**

`AnalyticsSettings.java`에서 `KEY_RECENT_WINDOW` 상수 선언 바로 아래에 추가:

```java
	/** 동시 처리(병렬) 개수 — LLM 콜 처리량 개선(2026-07-23). Vertex는 RPM 페이싱이 없어(DSQ)
	 * 병렬화 여유가 있고, Gemini로 되돌려도 GeminiHttpApi.pace()가 synchronized라 안전하게
	 * 감속된다. 429 빈도를 보며 재배포 없이 조정할 수 있게 app_setting으로 뺀다. */
	public static final String KEY_ANALYZE_CONCURRENCY = "analytics.analyze-concurrency";
```

`DEFAULT_RECENT_WINDOW` 상수 선언 바로 아래에 추가:

```java
	static final int DEFAULT_ANALYZE_CONCURRENCY = 8;
```

`recentWindow()` 메서드 바로 아래에 추가:

```java
	public int analyzeConcurrency() {
		return read(KEY_ANALYZE_CONCURRENCY).map(Integer::parseInt).orElse(DEFAULT_ANALYZE_CONCURRENCY);
	}
```

- [ ] **Step 4: 테스트 실행 — 통과 확인**

Run: `./gradlew :analytics:test --tests "com.celfit.analytics.analyze.ContentAnalysisJobTest.동시_처리_개수_기본값과_app_setting_오버라이드"`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add analytics/src/main/java/com/celfit/analytics/config/AnalyticsSettings.java \
        analytics/src/test/java/com/celfit/analytics/analyze/ContentAnalysisJobTest.java
git commit -m "feat(analytics): AnalyticsSettings에 analyzeConcurrency() 추가"
```

---

## Task 2: 운영 기준값 Flyway 마이그레이션

**Files:**
- Create: `crawler/src/main/resources/db/migration/V20__analytics_analyze_concurrency.sql`

app_setting 기준값은 컨벤션대로 crawler Flyway 마이그레이션으로 시드한다(V16 패턴,
`ON CONFLICT DO NOTHING` — 런타임 오버라이드는 보존, "없으면 채우는 기준값").

- [ ] **Step 1: 마이그레이션 파일 작성**

`crawler/src/main/resources/db/migration/V20__analytics_analyze_concurrency.sql`:

```sql
-- 콘텐츠 분석 동시 처리(병렬) 개수 기준값 시드 (2026-07-23).
-- 배경: LIMIT 폐지(2026-07-23, timely/late_backfill 분리) 이후에도 처리 루프가 순차라 대량
-- 백필 적체 시 처리에 수 시간이 걸리는 문제를 운영에서 확인(실측 26,167건 적체). Vertex는
-- RPM 페이싱이 없어(DSQ) 병렬화 여유가 있다.
-- ON CONFLICT DO NOTHING: 운영 런타임 오버라이드 보존 — 이 시드는 "없으면 채우는 기준값".
INSERT INTO app_setting(key, value) VALUES
  ('analytics.analyze-concurrency', '8')
ON CONFLICT (key) DO NOTHING;
```

- [ ] **Step 2: Flyway 검증 테스트 실행**

Run: `./gradlew :crawler:test --tests "*FlywaySchemaTest*" --tests "*Flyway*"`
Expected: PASS (마이그레이션이 깨끗이 적용되는지 확인 — 체크섬·순번 충돌 없음)

- [ ] **Step 3: 커밋**

```bash
git add crawler/src/main/resources/db/migration/V20__analytics_analyze_concurrency.sql
git commit -m "feat(crawler): analytics.analyze-concurrency 기준값(8) Flyway 시드"
```

---

## Task 3: runQuery() 병렬화

핵심 동작 변경. 테스트를 먼저 새 동작에 맞게 고치고(빨강), 그다음 구현한다(초록).

**Files:**
- Modify: `analytics/src/test/java/com/celfit/analytics/analyze/ContentAnalysisJobTest.java`
- Modify: `analytics/src/main/java/com/celfit/analytics/analyze/ContentAnalysisJob.java`

- [ ] **Step 1: `분석_대상은_수집_최신순이다()`를 concurrency=1로 고정**

이 테스트는 `insightCalls`의 **호출 순서**를 검증한다(수집 최신순 — 썸네일 서명 URL 생존 우선순위).
병렬 처리에서는 완료 순서가 섞일 수 있어, 이 테스트만 `analytics.analyze-concurrency=1`로 고정해
순차 처리를 강제한다(제출 순서는 병렬이든 아니든 항상 최신순 유지 — Task 3에서 확정할 설계).

현재:

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

를 다음으로 교체:

```java
	@Test
	void 분석_대상은_수집_최신순이다() {
		// 썸네일 서명 URL이 살아있을 때 VLM을 먼저 시도하기 위해 최신 수집분부터 처리한다.
		// LIMIT을 없앴으므로(전량 처리) 순서는 insightCalls 호출 순서로 검증한다.
		// 병렬 처리(기본 concurrency=8)에서는 완료 순서가 섞일 수 있어 concurrency=1로 고정해
		// 순서를 결정적으로 만든다 — 제출 순서(=최신순)는 병렬 여부와 무관하게 항상 유지된다.
		db.update("INSERT INTO app_setting(key, value) VALUES ('analytics.analyze-concurrency', '1')");

		int processed = job.run().processed();

		assertEquals(2, processed);
		assertEquals("post_b", insightCalls.get(0).shortCode()); // captured_at 최신
		assertEquals("post_a", insightCalls.get(1).shortCode());
	}
```

- [ ] **Step 2: 쿼타 소진 스킵 회귀 테스트 추가**

`일_한도_소진이면_배치를_중단하고_잔여를_이월한다()` 테스트(384~401행 근방) 바로 뒤에 추가.
이 새 테스트는 "쿼타 소진 플래그가 서면 아직 시작 안 한 대상은 LLM 호출 자체를 안 한다"는
병렬화의 핵심 안전장치를 검증한다 — `concurrency=1`로 고정해 처리 순서를 결정적으로 만들고,
3번째 대상까지 준비한 뒤 최신순 첫 대상에서 소진시켜 나머지 2건이 `insight.analyze()`를
아예 안 타는지 확인한다:

```java
	@Test
	void 쿼타_소진_플래그가_서면_이후_대상은_LLM_호출_없이_스킵된다() {
		// 병렬화(2026-07-23) 후에도 쿼타 소진 후 남은 큐가 추가로 429를 만들며 시간을 낭비하지
		// 않아야 한다. concurrency=1로 고정해 순서를 결정적으로 만들고, 최신순 첫 대상(post_b)에서
		// 소진시켜 나머지(post_a, post_0)가 insight.analyze() 자체를 안 타는지 확인한다.
		db.update("INSERT INTO app_setting(key, value) VALUES ('analytics.analyze-concurrency', '1')");
		db.update("""
				INSERT INTO contents (short_code, account_handle, thumbnail_url, caption, content_type, posted_at, metric_captured_at, views, likes, comments)
				VALUES ('post_0', 'acct1', 'https://img/0.jpg', '캡션0', 'reels', now() - interval '10 days', now() - interval '6 days 22 hours', 5000, 100, 10)""");
		db.update("""
				INSERT INTO content_comments (id, short_code, author_masked, body, like_count)
				VALUES (10, 'post_0', 'ddd***', '굿', 0)""");
		db.update("""
				INSERT INTO comment_classifications (id, short_code, ai_category, model)
				VALUES (10, 'post_0', 'positive', 'claude-test')""");
		List<String> attempted = new ArrayList<>();
		rewireJob((content, thumbnailUrl) -> {
			attempted.add(content.shortCode());
			throw new com.celfit.analytics.llm.LlmQuotaExhaustedException("일 한도");
		}, false);

		int processed = job.run().processed();

		assertEquals(0, processed);
		assertEquals(1, attempted.size()); // 최신순 첫 대상(post_b)에서 소진 — 나머지 2건은 호출 자체가 없음
		assertEquals("post_b", attempted.get(0));
		assertEquals(0L, db.queryForObject("SELECT count(*) FROM content_analyses", Long.class));
	}
```

- [ ] **Step 3: 테스트 실행 — 현재 상태 확인 (진짜 red는 아님, 이유는 아래 참고)**

Run: `./gradlew :analytics:test --tests "com.celfit.analytics.analyze.ContentAnalysisJobTest"`
Expected: **두 테스트 다 이미 PASS일 가능성이 높다.** `concurrency=1`을 강제하면 "한 번에 하나씩,
순서대로, 쿼타 소진 시 즉시 멈춤"이라는 관찰 가능한 동작이 지금의 순차 `break` 로직과 똑같아지기
때문 — 옛 코드도 사실상 "동시성=1"이라 이 두 테스트는 진짜 TDD red 상태가 아니다(컴파일도 이미
Task 1에서 `analyzeConcurrency()`를 추가해둬서 문제없음). 이 단계의 목적은 "지금 통과 vs
안 통과"가 아니라, **Step 5 구현 후에도 계속 통과해야 하는 회귀 기준선을 먼저 고정**하는 것 —
동시성 자체(병렬 처리로 처리량이 늘어난다는 것)는 이 단위 테스트로는 직접 검증하기 어렵다
(스레드 스케줄링을 강제로 통제해야 해서 결정적 테스트 작성 비용이 큼). Step 5 구현 후 Step 6에서
두 테스트가 여전히 통과하는지 + 전체 스위트가 깨지지 않는지로 검증한다.

- [ ] **Step 4: ContentAnalysisJob.java import 추가**

파일 상단 import 블록에 추가:

```java
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
```

- [ ] **Step 5: `runQuery()` 구현 교체**

현재 `runQuery()` 메서드 전체를:

```java
	private JobResult runQuery(String sql, Object[] params, boolean timely, ProgressReporter progress) {
		Baselines baselines = loadBaselines();
		List<String> targets = new ArrayList<>();
		analysis.query(sql, rs -> {
			targets.add(rs.getString(1));
		}, params);
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

로 교체:

```java
	private JobResult runQuery(String sql, Object[] params, boolean timely, ProgressReporter progress) {
		Baselines baselines = loadBaselines();
		List<String> targets = new ArrayList<>();
		analysis.query(sql, rs -> {
			targets.add(rs.getString(1));
		}, params);
		String model = settings.activeLlmModel();
		AtomicInteger processedCount = new AtomicInteger();
		AtomicInteger failedCount = new AtomicInteger();
		AtomicBoolean quotaExhausted = new AtomicBoolean();
		progress.report(0, 0, targets.size());

		// 대상은 제출 순서(=쿼리의 최신순)를 유지한 채 병렬 처리한다 — 고정 크기 풀의 작업 큐는
		// FIFO라 "최신 수집분부터"(썸네일 서명 URL 생존 우선순위, B3) 의도는 유지되고 완료
		// 순서만 동시성 때문에 섞인다. 병렬도는 app_setting(analytics.analyze-concurrency,
		// 기본 8)으로 재배포 없이 조정 가능 — Vertex는 RPM 페이싱이 없어(DSQ) 여유가 있다.
		List<Callable<Void>> tasks = new ArrayList<>();
		for (String shortCode : targets) {
			tasks.add(() -> {
				if (quotaExhausted.get()) {
					return null; // 이미 쿼타 소진 — 남은 큐는 추가 429를 만들지 않도록 LLM 호출 없이 스킵
				}
				try {
					analyzeOne(shortCode, model, baselines.withBaseline(), baselines.accountBaseline(), timely);
					int p = processedCount.incrementAndGet();
					progress.report(p, failedCount.get(), targets.size());
				} catch (com.celfit.analytics.llm.LlmQuotaExhaustedException e) {
					// 일 한도 소진 — 에러가 아닌 이월: 남은 대상은 다음 실행에서 자연 재대상 (07-18
					// 확정, 병렬화 후에도 유지). 이미 진행 중이던 다른 작업은 강제 취소하지 않고
					// 완료시킨다 — 콜 자체가 짧아(초 단위) 취소로 얻는 이득보다 부분 상태 복잡도가 크다.
					quotaExhausted.set(true);
					log.warn("LLM 일 한도 소진 — {} 이후 대상은 스킵(이월)", shortCode);
				} catch (Exception e) {
					int f = failedCount.incrementAndGet();
					log.error("analysis failed for {} — 다음 실행에서 재대상", shortCode, e);
					progress.report(processedCount.get(), f, targets.size());
				}
				return null;
			});
		}

		int concurrency = Math.max(1, settings.analyzeConcurrency());
		try (ExecutorService pool = Executors.newFixedThreadPool(concurrency)) {
			pool.invokeAll(tasks);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("분석 배치가 인터럽트됨", e);
		}

		int processed = processedCount.get();
		int failed = failedCount.get();
		boolean carriedOver = quotaExhausted.get();
		// 풀 종료 후 최종 수치로 한 번 더 보고 — 동시 완료 시 마지막 개별 report 호출이 진짜
		// 최종값이라는 보장이 없어, 이게 없으면 어드민 진행률 UI가 부정확한 값으로 끝날 수 있다.
		progress.report(processed, failed, targets.size());
		log.info("analysis complete ({} contents, {} failed, quota carried over={})",
				processed, failed, carriedOver);
		return new JobResult(processed, failed, carriedOver);
	}
```

- [ ] **Step 6: 테스트 실행 — 전체 클래스 통과 확인**

Run: `./gradlew :analytics:test --tests "com.celfit.analytics.analyze.ContentAnalysisJobTest"`
Expected: PASS (33개 전체 — 기존 31개 + Task 1의 설정 테스트 1개 + Task 3의 신규 스킵 테스트 1개).
샌드박스에서 Testcontainers를 실행할 수 있으면 `TESTCONTAINERS_RYUK_DISABLED=true
DOCKER_HOST=unix:///Users/woomin/.colima/default/docker.sock`를 export하고 `--rerun-tasks`로
캐시를 우회해 진짜 실행 결과를 확인한다.

- [ ] **Step 7: 커밋**

```bash
git add analytics/src/main/java/com/celfit/analytics/analyze/ContentAnalysisJob.java \
        analytics/src/test/java/com/celfit/analytics/analyze/ContentAnalysisJobTest.java
git commit -m "feat(analytics): ContentAnalysisJob 처리 루프를 동시 처리(병렬)로 전환"
```

---

## Task 4: 전체 검증

**Files:** 없음(실행만)

- [ ] **Step 1: analytics 모듈 전체 테스트**

Run: `export TESTCONTAINERS_RYUK_DISABLED=true DOCKER_HOST=unix:///Users/woomin/.colima/default/docker.sock && ./gradlew :analytics:test --rerun-tasks`
Expected: BUILD SUCCESSFUL, 전 테스트 PASS. 콘솔 요약이 아니라
`analytics/build/test-results/test/*.xml`의 `tests=/failures=/errors=` 속성으로 실제 실행
결과를 확인한다(캐시된 UP-TO-DATE 결과 신뢰 금지).

- [ ] **Step 2: 전체 멀티모듈 빌드**

Run: `./gradlew test --rerun-tasks`
Expected: BUILD SUCCESSFUL (crawler·was·analytics·contract-analysis)

- [ ] **Step 3: 스펙 문서 상태 헤더 갱신**

`docs/superpowers/specs/2026-07-23-content-analysis-concurrency-design.md` 1행을

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
git add docs/superpowers/specs/2026-07-23-content-analysis-concurrency-design.md
git commit -m "docs: 동시 처리 스펙 상태를 구현됨으로 갱신"
```

---

## 운영 반영 (이 계획 밖 — 참고용)

머지·배포 후, 필요하면 운영 `analytics.analyze-concurrency` 값을 429 빈도를 보며 조정한다
(SQL `UPDATE app_setting SET value = 'N' WHERE key = 'analytics.analyze-concurrency'` — 런타임
토글이라 재배포 불필요). 지금 밀린 26,167건 백필은 이 PR이 배포되고 나면 다음
`runLateBackfill()` 실행부터 훨씬 빠르게 소진된다.
