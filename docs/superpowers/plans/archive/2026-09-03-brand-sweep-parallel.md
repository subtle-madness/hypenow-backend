# 브랜드 태그 스윕 브랜드 단위 병렬화 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

> 상태: ✅ 실행됨 · 설계: [specs/2026-09-03-brand-sweep-parallel-design.md](../../specs/2026-09-03-brand-sweep-parallel-design.md)

**Goal:** 야간 브랜드 태그 스윕(`BrandSweepJob`)의 브랜드 루프를 고정 풀 4스레드로 병렬화해 6시간 44분을 105~140분으로 줄인다.

**Architecture:** 브랜드 1건 = 태스크 1건을 전용 executor(`brandSweepExecutor`)에 제출하고 전부 완료를 기다린 뒤 기존 finally(광고 백필·아카이브)를 돈다. 브랜드 안의 3단계 순서·격리·touchSwept 규칙은 불변. 전날 `brand_call_count` 콜 수 내림차순으로 무거운 브랜드부터 배정한다. direct 2단계의 서비스 전역 busy 가드는 브랜드 키 집합으로 바꾼다.

**Tech Stack:** Java 21, Spring Boot 4.1 (`@Value`·`@Qualifier`·`@Bean` executor), `CompletableFuture`, JdbcTemplate, JUnit 5 + AssertJ, Testcontainers(BrandStoreTest 관용구 `TestDb`).

## Global Constraints

- 주석·로그·커밋 메시지는 한국어. 커밋 prefix `feat(monitoring):`/`test(monitoring):`/`docs:`.
- 테스트는 모듈 단위: `./gradlew :monitoring:test --tests "..."`. 전체 `./gradlew test`는 PR 직전에만.
- 로컬 도커는 Docker Desktop — `DOCKER_HOST` 미설정이 정답(메모리 노트).
- 설정 키 `monitoring.brand.sweep-concurrency`, 기본 **4**. 롤백은 env `MONITORING_BRAND_SWEEP_CONCURRENCY=1`.
- Hikari `maximum-pool-size: 20`. 전역 Hiker 동시 콜 예산 문서값 **14 → 17**.
- Hiker 콜 수·과금 불변. 브랜드 안 3단계 순서(태그→direct→해시태그) 불변.

---

### Task 1: direct 2단계 busy 가드를 브랜드 키로

**Files:**
- Modify: `monitoring/src/main/java/com/celfit/monitoring/service/BrandDirectCollectService.java:58-71` (필드·javadoc), `:131-142` (`sweepUnenumerated`), `:212-221` (`backfillUnenriched`)
- Test: `monitoring/src/test/java/com/celfit/monitoring/service/BrandDirectCollectServiceTest.java:621-659`

**Interfaces:**
- Produces: package-private `final Set<Long> busyBrands` (`ConcurrentHashMap.newKeySet()`) — 테스트가 겹침 상태를 주입하는 손잡이. `unenumeratedBusy` 필드는 제거.

- [ ] **Step 1: 기존 두 테스트를 브랜드 키 API로 옮기고, 다른 브랜드는 막히지 않는 테스트를 추가**

`BrandDirectCollectServiceTest.java` 621행 이후의 두 테스트를 아래로 교체하고 세 번째를 추가한다.

```java
	// ── busyBrands 동시 실행 가드(2026-08-28 리뷰 지적 → 2026-09-03 브랜드 키화) ───────

	/**
	 * sweepUnenumerated(야간 스윕 2단계)가 <b>같은 브랜드</b>를 처리 중일 때 기동 백필이 같은 게시물을
	 * 겹쳐 Hiker에 이중 과금하지 않도록, 겹침이면 backfillUnenriched는 즉시 0을 반환하고 콜을 내지
	 * 않는다. 실제 스레드 경합 대신 package-private 집합으로 겹침 상태를 결정적으로 주입한다.
	 */
	@Test
	void 같은_브랜드가_겹치면_backfillUnenriched가_콜_없이_0을_반환한다() {
		tagged.unenrichedDue.add(new TaggedPostRepository.TrackedPost("Busy", Instant.ofEpochSecond(RECENT), null));
		postResponses.put("Busy", postJson("Busy", RECENT, 403));
		BrandDirectCollectService svc = service();
		svc.busyBrands.add(brand.id());   // sweepUnenumerated가 이 브랜드를 처리 중이라고 가정

		int backfilled = svc.backfillUnenriched(brand);

		assertThat(backfilled).isZero();
		assertThat(postCalls()).isZero();
		assertThat(tagged.enriched).isEmpty();
	}

	/**
	 * 브랜드 스윕 병렬화(2026-09-03) 전제 — 가드는 브랜드 단위다. 다른 브랜드가 처리 중이어도 이
	 * 브랜드는 평소대로 돈다(구 전역 AtomicBoolean은 병렬 스윕에서 서로의 2단계를 매일 건너뛰게 했다).
	 */
	@Test
	void 다른_브랜드가_처리_중이어도_이_브랜드는_막히지_않는다() {
		tagged.unenrichedDue.add(new TaggedPostRepository.TrackedPost("Free", Instant.ofEpochSecond(RECENT), null));
		postResponses.put("Free", postJson("Free", RECENT, 404));
		BrandDirectCollectService svc = service();
		svc.busyBrands.add(99L);   // 다른 브랜드(id 99)가 병렬 스윕 중

		int backfilled = svc.backfillUnenriched(brand);

		assertThat(backfilled).isEqualTo(1);
		assertThat(tagged.enriched).containsExactly("Free");
		assertThat(svc.busyBrands).containsExactly(99L);   // 이 브랜드 것만 해제, 남의 것은 건드리지 않는다
	}

	/** 겹침이 없으면 평소대로 동작하고 처리 후 해제된다 — 가드가 정상 경로를 막지 않는다는 회귀 방지. */
	@Test
	void 겹침이_없으면_backfillUnenriched는_평소대로_동작한다() {
		tagged.unenrichedDue.add(new TaggedPostRepository.TrackedPost("Free", Instant.ofEpochSecond(RECENT), null));
		postResponses.put("Free", postJson("Free", RECENT, 404));
		BrandDirectCollectService svc = service();

		int backfilled = svc.backfillUnenriched(brand);

		assertThat(backfilled).isEqualTo(1);
		assertThat(tagged.enriched).containsExactly("Free");
		assertThat(svc.busyBrands).isEmpty();   // 처리 후 해제됨 — 다음 호출을 막지 않는다
	}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :monitoring:test --tests "com.celfit.monitoring.service.BrandDirectCollectServiceTest"`
Expected: 컴파일 실패 — `busyBrands` 심볼 없음.

- [ ] **Step 3: 구현**

`BrandDirectCollectService.java` — import에 `java.util.Set`·`java.util.concurrent.ConcurrentHashMap` 추가, `java.util.concurrent.atomic.AtomicBoolean` import 제거. 필드·javadoc(58~71행)을 교체:

```java
	/**
	 * unenumerated 처리 동시 실행 가드(2026-08-28 리뷰 지적, 2026-09-03 브랜드 키화) — {@link
	 * #sweepUnenumerated}(야간 스윕 2단계)와 {@link #backfillUnenriched}(기동 즉시 백필)는 같은 모수
	 * (direct∪hashtag 미크롤 행)를 겹쳐 건드릴 수 있다(배포 재기동이 새벽 스윕 시간대 근처에 걸리면).
	 * <b>같은 브랜드</b>의 겹침만 막는다 — 두 진입점 다 브랜드 1건 처리 단위로 획득·해제하므로, 겹치면
	 * 그 브랜드(그 호출) 한 건만 스킵되고 데이터가 깨지지는 않는다(같은 게시물을 두 콜이 동시에
	 * Hiker에 이중 과금하는 것만 막는 목적 — upsert·markEnriched 자체는 멱등이라 스킵된 쪽은 다음
	 * 스윕이나 다음 기동이 다시 잡는다).
	 *
	 * <p>구 서비스 전역 AtomicBoolean은 브랜드 스윕 병렬화(2026-09-03 설계 §3-2)와 양립하지 않았다 —
	 * 브랜드 4개가 동시에 돌면 서로의 2단계를 "겹침"으로 건너뛰어 그날 2단계가 통째로 빠진다.
	 *
	 * <p>package-private으로 열어 테스트가 겹침 상태를 직접 주입할 수 있게 한다(동시 호출 타이밍을
	 * 실제 스레드 경합으로 재현하지 않고 결정적으로 검증하기 위함 — {@code judgeOne}과 같은 이유).
	 */
	final Set<Long> busyBrands = ConcurrentHashMap.newKeySet();
```

`sweepUnenumerated`:

```java
	public void sweepUnenumerated(BrandRow brand) {
		if (!busyBrands.add(brand.id())) {
			log.info("unenumerated 처리 겹침 - 이번 호출 스킵 brand={}", brand.username());
			return;
		}
		try {
			callContext.scoped(brand.id(), () -> {
				doSweepUnenumerated(brand);
				return null;
			});
		} finally {
			busyBrands.remove(brand.id());
		}
	}
```

`backfillUnenriched`:

```java
	public int backfillUnenriched(BrandRow brand) {
		if (!busyBrands.add(brand.id())) {
			log.info("unenumerated 처리 겹침 - 이번 호출 스킵 brand={}", brand.username());
			return 0;
		}
		try {
			return callContext.scoped(brand.id(), () -> doBackfillUnenriched(brand));
		} finally {
			busyBrands.remove(brand.id());
		}
	}
```

`sweepUnenumerated` javadoc(128행 근처)의 "`{@link #unenumeratedBusy}`"와 `backfillUnenriched` javadoc의 같은 언급을 "`{@link #busyBrands}`"로 바꾼다.

- [ ] **Step 4: 통과 확인**

Run: `./gradlew :monitoring:test --tests "com.celfit.monitoring.service.BrandDirectCollectServiceTest"`
Expected: PASS (전체 클래스).

- [ ] **Step 5: 커밋**

```bash
git add monitoring/src/main/java/com/celfit/monitoring/service/BrandDirectCollectService.java monitoring/src/test/java/com/celfit/monitoring/service/BrandDirectCollectServiceTest.java
git commit -m "feat(monitoring): direct 2단계 겹침 가드를 브랜드 키 집합으로 — 병렬 스윕이 서로의 2단계를 건너뛰지 않게"
```

---

### Task 2: 무거운 브랜드 우선 조회 `findActiveHeaviestFirst`

**Files:**
- Modify: `monitoring/src/main/java/com/celfit/monitoring/store/BrandRepository.java:169-174` 뒤에 메서드 추가
- Test: `monitoring/src/test/java/com/celfit/monitoring/store/BrandStoreTest.java` (파일 끝에 테스트 추가)

**Interfaces:**
- Produces: `public List<BrandRow> findActiveHeaviestFirst(LocalDate calledOn)` — ACTIVE 브랜드를 `brand_call_count.calls`(해당 `calledOn`) 내림차순, 동률·이력 없음은 `id` 오름차순. 기존 `findActive()`는 다른 호출처가 있어 그대로 둔다.

- [ ] **Step 1: 실패하는 테스트 작성**

`BrandStoreTest.java` 파일 끝(마지막 `}` 앞)에 추가. `BrandCallCountRepository`는 같은 패키지라 import 불필요.

```java
	/**
	 * 브랜드 스윕 병렬화(2026-09-03 설계 §3-1) — LPT 배정 입력. 전날 콜 수가 "그 브랜드가 실제로
	 * 얼마나 무거웠나"의 정본이라 별도 추정 컬럼 없이 이 순서로 제출한다.
	 */
	@Test
	void findActiveHeaviestFirst는_해당일_콜_수_내림차순이고_이력_없는_브랜드는_뒤로_간다() {
		long light = brands.insertOrReactivate("light", profile("light", "1", 10L, ""), 12, true);
		long heavy = brands.insertOrReactivate("heavy", profile("heavy", "2", 10L, ""), 12, true);
		long none = brands.insertOrReactivate("none", profile("none", "3", 10L, ""), 12, true);
		long closed = brands.insertOrReactivate("closed", profile("closed", "4", 10L, ""), 12, true);
		brands.close("closed");
		var calls = new BrandCallCountRepository(db);
		LocalDate day = LocalDate.of(2026, 9, 2);
		calls.add(light, day, 5);
		calls.add(heavy, day, 500);
		calls.add(closed, day, 9_999);          // 닫힌 브랜드는 콜이 많아도 제외
		calls.add(none, day.minusDays(1), 800); // 다른 날짜 콜은 무시

		List<Long> ids = brands.findActiveHeaviestFirst(day).stream().map(BrandRow::id).toList();

		assertThat(ids).containsExactly(heavy, light, none);
	}
```

`LocalDate`·`List` import가 없으면 추가한다(`java.time.LocalDate`, `java.util.List`).

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :monitoring:test --tests "com.celfit.monitoring.store.BrandStoreTest"`
Expected: 컴파일 실패 — `findActiveHeaviestFirst` 심볼 없음.

- [ ] **Step 3: 구현**

`BrandRepository.java`의 `findActive()` 바로 아래에 추가:

```java
	/**
	 * 활성 브랜드를 <b>무거운 순</b>으로 — 브랜드 스윕 병렬화(2026-09-03 설계 §3-1)의 LPT 배정 입력.
	 * 무거움의 정본은 {@code calledOn}(KST 달력일)의 {@code brand_call_count.calls}다: 직전 스윕(전날
	 * KST 02:00~)의 콜이 그 날짜에 계상되므로 호출부는 "KST 오늘 − 1일"을 넘긴다. 이력 없는 브랜드는
	 * 0으로 맨 뒤, 동률은 id 순(결정적). 전날 등록된 브랜드는 백필 콜로 앞에 서는데 무해하다(먼저 돌
	 * 뿐). 다른 호출처(기동 러너들)는 순서가 무의미해 {@link #findActive()}를 그대로 쓴다.
	 */
	public List<BrandRow> findActiveHeaviestFirst(LocalDate calledOn) {
		return db.query("""
				SELECT b.id, b.username, b.ig_user_id, b.status, b.last_swept_on, b.collection_months, b.has_own_link
				FROM brand_account b
				LEFT JOIN brand_call_count c ON c.brand_id = b.id AND c.called_on = ?
				WHERE b.status = 'ACTIVE'
				ORDER BY COALESCE(c.calls, 0) DESC, b.id""",
				BrandRepository::toRow, calledOn);
	}
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew :monitoring:test --tests "com.celfit.monitoring.store.BrandStoreTest"`
Expected: PASS.

- [ ] **Step 5: 커밋**

```bash
git add monitoring/src/main/java/com/celfit/monitoring/store/BrandRepository.java monitoring/src/test/java/com/celfit/monitoring/store/BrandStoreTest.java
git commit -m "feat(monitoring): 활성 브랜드를 전날 콜 수 내림차순으로 조회하는 findActiveHeaviestFirst — 병렬 스윕 LPT 배정 입력"
```

---

### Task 3: `BrandSweepJob` 브랜드 단위 병렬 실행 + 전용 executor + 브랜드별 소요 로그

**Files:**
- Modify: `monitoring/src/main/java/com/celfit/monitoring/config/BrandBackfillConfig.java` (클래스 javadoc의 예산 문단 + `brandSweepExecutor` 빈 추가)
- Modify: `monitoring/src/main/java/com/celfit/monitoring/service/BrandSweepJob.java` (생성자·`runSweep`)
- Modify: `monitoring/src/main/resources/application.yml:89-111` (`sweep-concurrency` 키 + 예산 주석)
- Test: `monitoring/src/test/java/com/celfit/monitoring/service/BrandSweepJobTest.java`

**Interfaces:**
- Consumes: Task 2의 `BrandRepository.findActiveHeaviestFirst(LocalDate)`.
- Produces: `BrandSweepJob` 생성자 마지막 파라미터 `@Qualifier("brandSweepExecutor") Executor sweepExecutor`. 빈 `brandSweepExecutor`(`monitoring.brand.sweep-concurrency:4`).

- [ ] **Step 1: 기존 테스트를 새 생성자·조회 메서드로 옮기고 병렬 테스트 4개 추가**

(a) `BrandSweepJobTest.java`의 생성자 호출 전부에 마지막 인자 `Runnable::run`(동기 executor — 제출 즉시 호출 스레드에서 실행되어 기존 순서 단언이 그대로 성립)을 붙인다:

```bash
sed -i '' -e 's/adJudge, adDisclosureEnabled);/adJudge, adDisclosureEnabled, Runnable::run);/' \
  -e 's/new StubAdJudge(), true)/new StubAdJudge(), true, Runnable::run)/g' \
  -e 's/hashtagAuthorArchive, adJudge, true)/hashtagAuthorArchive, adJudge, true, Runnable::run)/' \
  monitoring/src/test/java/com/celfit/monitoring/service/BrandSweepJobTest.java
grep -c 'Runnable::run' monitoring/src/test/java/com/celfit/monitoring/service/BrandSweepJobTest.java
```
Expected: 생성자 호출 수와 같은 개수(약 8). `grep -n 'new BrandSweepJob(' ...`로 누락이 없는지 눈으로 확인한다.

(b) `StubBrands`를 병렬 안전 + 새 조회 메서드로 교체:

```java
	private static final class StubBrands extends BrandRepository {
		List<BrandRow> active = List.of();
		final List<Long> touched = new CopyOnWriteArrayList<>();   // 병렬 스윕에서 여러 스레드가 기록
		/** 계정 게이트 호출 관측(2026-08-18) — 야간 스윕은 markServing을 부르지 않는다는 회귀 방지. */
		final List<Long> served = new CopyOnWriteArrayList<>();
		/** 스윕이 넘긴 콜 집계 기준일 — "KST 오늘 − 1일" 계약 검증. */
		LocalDate calledOn;

		StubBrands() {
			super(null);
		}

		@Override
		public List<BrandRow> findActiveHeaviestFirst(LocalDate calledOn) {
			this.calledOn = calledOn;
			return active;
		}

		@Override
		public void touchSwept(long brandId, LocalDate on) {
			touched.add(brandId);
		}

		@Override
		public void markServing(long brandId) {
			served.add(brandId);
		}
	}
```

`StubCollect`·`StubDirectCollect`·`StubHashtagCollect`의 `swept`도 `new CopyOnWriteArrayList<>()`로 바꾼다(`failing`은 읽기 전용이라 그대로).
`StubArchive`는 아래 테스트가 익명 하위 클래스로 감싸므로 선언의 `final`을 뺀다(`private static class StubArchive`).

(c) `스윕이_예외로_이탈해도_아카이브는_실행된다`의 익명 `BrandRepository`는 `findActiveHeaviestFirst(LocalDate calledOn)`을 오버라이드해 던지도록 바꾼다.

(d) import 추가: `java.util.concurrent.CopyOnWriteArrayList`, `java.util.concurrent.CountDownLatch`, `java.util.concurrent.ExecutorService`, `java.util.concurrent.Executors`, `java.util.concurrent.TimeUnit`, `java.util.concurrent.atomic.AtomicInteger`, `java.time.ZoneId`.

(e) 새 테스트 4개를 파일 끝에 추가:

```java
	// ── 브랜드 단위 병렬 실행(2026-09-03 설계 §3-1) ─────────────────────────────

	/** 두 브랜드가 동시에 스윕 안에 들어와야 래치가 열린다 — 직렬이면 첫 브랜드가 영원히 기다려 타임아웃. */
	private static final class RendezvousCollect extends BrandCollectService {
		final CountDownLatch inside = new CountDownLatch(2);
		final AtomicInteger timedOut = new AtomicInteger();

		RendezvousCollect() {
			super(null, null, null, null, null, null, null, null, null, null, null, null,
					2000, 10000, 3, 30, true);
		}

		@Override
		public void sweep(BrandRow brand) {
			inside.countDown();
			try {
				if (!inside.await(2, TimeUnit.SECONDS)) {
					timedOut.incrementAndGet();
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
	}

	@Test
	void 브랜드들은_executor_스레드에서_동시에_스윕된다() throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			var brands = new StubBrands();
			var collect = new RendezvousCollect();
			brands.active = List.of(brand(1, "first"), brand(2, "second"));

			new BrandSweepJob(brands, collect, new StubDirectCollect(), new StubHashtagCollect(), new StubArchive(),
					new StubBrandArchive(), new StubPostThumbArchive(), new StubHashtagThumbArchive(),
					new StubHashtagAuthorArchive(), new StubAdJudge(), true, pool).run();

			assertThat(collect.timedOut).hasValue(0);          // 둘 다 상대를 만났다 = 동시 실행
			assertThat(brands.touched).containsExactlyInAnyOrder(1L, 2L);
		} finally {
			pool.shutdownNow();
		}
	}

	@Test
	void 병렬_실행에서도_실패_격리와_카운터가_유지되고_아카이브는_전_브랜드_완료_뒤에_돈다() throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(4);
		try {
			var brands = new StubBrands();
			var collect = new StubCollect();
			collect.failing.add("boom");
			var directCollect = new StubDirectCollect();
			directCollect.failing.add("third");
			var hashtagCollect = new StubHashtagCollect();
			brands.active = List.of(brand(1, "first"), brand(2, "boom"), brand(3, "third"), brand(4, "fourth"));
			// 아카이브가 돌 때 스윕이 몇 건 끝나 있었는지 스냅샷 — 전 브랜드 완료 뒤여야 한다
			var sweptWhenArchived = new AtomicInteger(-1);
			var archive = new StubArchive() {
				@Override
				public void run() {
					sweptWhenArchived.set(collect.swept.size());
					super.run();
				}
			};

			new BrandSweepJob(brands, collect, directCollect, hashtagCollect, archive, new StubBrandArchive(),
					new StubPostThumbArchive(), new StubHashtagThumbArchive(), new StubHashtagAuthorArchive(),
					new StubAdJudge(), true, pool).run();   // 예외가 새면 여기서 터진다

			assertThat(collect.swept).containsExactlyInAnyOrder("first", "third", "fourth");
			assertThat(brands.touched).containsExactlyInAnyOrder(1L, 3L, 4L);   // boom은 "준비 중" 유지
			assertThat(directCollect.swept).containsExactlyInAnyOrder("first", "boom", "fourth");
			assertThat(hashtagCollect.swept).containsExactlyInAnyOrder("first", "boom", "third", "fourth");
			assertThat(sweptWhenArchived).hasValue(3);   // 아카이브는 join 뒤 — 스윕이 전부 끝난 상태
		} finally {
			pool.shutdownNow();
		}
	}

	@Test
	void 동시성_1이면_제출_순서대로_직렬_실행된다() throws Exception {
		ExecutorService single = Executors.newSingleThreadExecutor();
		try {
			var brands = new StubBrands();
			var collect = new StubCollect();
			brands.active = List.of(brand(1, "first"), brand(2, "second"), brand(3, "third"));

			new BrandSweepJob(brands, collect, new StubDirectCollect(), new StubHashtagCollect(), new StubArchive(),
					new StubBrandArchive(), new StubPostThumbArchive(), new StubHashtagThumbArchive(),
					new StubHashtagAuthorArchive(), new StubAdJudge(), true, single).run();

			assertThat(collect.swept).containsExactly("first", "second", "third");   // 롤백(동시성 1) = 현행 직렬
		} finally {
			single.shutdownNow();
		}
	}

	/** LPT 배정 입력 — 직전 스윕의 콜이 계상된 날짜(KST 오늘 − 1일)로 조회한다. */
	@Test
	void 브랜드_조회는_KST_전날_콜_수_기준이다() {
		var brands = new StubBrands();
		brands.active = List.of(brand(1, "first"));

		sweepJob(brands, new StubCollect(), new StubHashtagCollect(), new StubArchive(), new StubBrandArchive()).run();

		assertThat(brands.calledOn).isEqualTo(LocalDate.now(ZoneId.of("Asia/Seoul")).minusDays(1));
	}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :monitoring:test --tests "com.celfit.monitoring.service.BrandSweepJobTest"`
Expected: 컴파일 실패 — 생성자 12번째 인자 없음.

- [ ] **Step 3: executor 빈 추가**

`BrandBackfillConfig.java`: 클래스 javadoc의 예산 문장 "전역 동시 콜은 스윕과 등록 백필이 겹치는 최악의 경우 워커 10 + 스윕 core 1 + 등록 core 2 + 해시태그 스윕 1 = <b>최대 14</b>이다 (08-18 …)"를 다음으로 교체:

```
 * 전역 동시 콜은 스윕과 등록 백필이 겹치는 최악의 경우 워커 10 + 스윕 core 4 + 등록 core 2 + 해시태그 스윕 1 = <b>최대 17</b>이다
 * (2026-09-03 브랜드 스윕 병렬화로 스윕 core 1 → 4. 08-18 해시태그 스윕 전용 executor 분리 시점엔 14,
 * 08-13 워커 상향 전에는 9였다). 08-12 운영 서버 동시성 램프 실측(레벨당 30콜)에서는 동시 20까지
```

그리고 `brandHashtagSweepExecutor` 빈 아래에 추가:

```java
	/**
	 * 야간 브랜드 스윕의 브랜드 단위 병렬 풀(2026-09-03 설계 §3-1) — 브랜드 1건 = 태스크 1건.
	 * 스윕 6시간 44분(139브랜드, 직렬)의 본체는 Hiker 응답 대기라 CPU는 거의 안 쓴다(컨테이너 평균
	 * 0.03코어). 브랜드 간 의존이 없으므로 4스레드로 105~140분을 기대한다. 전역 Hiker 동시 콜은
	 * 스윕 core 1 → 4로 최악 17(클래스 javadoc) — 08-12 램프 실측 안전 구간(20) 안. 힙은 in-flight
	 * 콜당 ~10MB로 +30MB. 무제한 큐(다른 executor들과 같은 이유 — 유계 큐 + AbortPolicy는 제출
	 * 루프에서 동기 예외가 터져 격리 규칙을 깬다). 롤백은 동시성 1(env
	 * {@code MONITORING_BRAND_SWEEP_CONCURRENCY=1}) — 코드 경로가 같아 현행 직렬과 동일하게 돈다.
	 */
	@Bean(name = "brandSweepExecutor")
	public Executor brandSweepExecutor(
			@Value("${monitoring.brand.sweep-concurrency:4}") int concurrency) {
		AtomicInteger seq = new AtomicInteger();
		return Executors.newFixedThreadPool(concurrency, r -> {
			Thread t = new Thread(r, "brand-sweep-" + seq.incrementAndGet());
			t.setDaemon(true);
			return t;
		});
	}
```

- [ ] **Step 4: `BrandSweepJob` 병렬화**

import 추가: `java.util.ArrayList`, `java.util.concurrent.CompletableFuture`, `java.util.concurrent.Executor`, `java.util.concurrent.atomic.AtomicInteger`, `org.springframework.beans.factory.annotation.Qualifier`.

필드·생성자: `private final Executor sweepExecutor;` 추가, 생성자 마지막 파라미터 `@Qualifier("brandSweepExecutor") Executor sweepExecutor` 추가·대입.

클래스 javadoc 첫 문단 끝에 한 문단 추가:

```
 * <p>2026-09-03 브랜드 단위 병렬화(설계 §3-1) — 브랜드 1건 = 태스크 1건을 {@code brandSweepExecutor}
 * (기본 4스레드)에 제출하고 전부 끝나길 기다린다. 브랜드 간 의존이 없고 3단계가 이미 브랜드 단위로
 * 격리돼 있어 루프의 실행 형태만 바뀐다 — 브랜드 안의 순서(태그→direct→해시태그)·touchSwept 규칙은
 * 그대로다. 제출 순서는 전날 콜 수 내림차순(LPT — 무거운 브랜드가 꼬리에 오면 종료가 그만큼 밀린다).
 * 태스크는 예외를 밖으로 내지 않으므로 join()은 전 브랜드 완료만 뜻한다.
```

`runSweep`를 교체:

```java
	private void runSweep() {
		long startNanos = System.nanoTime();
		LocalDate today = LocalDate.now(KST);
		// 직전 스윕(전날 KST 02:00~)의 콜은 전날 날짜에 계상된다 — 그 순서로 무거운 브랜드부터 제출한다.
		List<BrandRow> active = brands.findActiveHeaviestFirst(today.minusDays(1));
		AtomicInteger failures = new AtomicInteger();
		AtomicInteger directFailures = new AtomicInteger();
		AtomicInteger hashtagFailures = new AtomicInteger();
		List<CompletableFuture<Void>> tasks = new ArrayList<>(active.size());
		for (BrandRow b : active) {
			tasks.add(CompletableFuture.runAsync(
					() -> sweepOne(b, today, failures, directFailures, hashtagFailures), sweepExecutor));
		}
		CompletableFuture.allOf(tasks.toArray(CompletableFuture[]::new)).join();
		log.info("브랜드 태그 스윕 완료 — 브랜드 {}건 중 실패 {}건, direct 실패 {}건, 해시태그 실패 {}건, 소요 {}ms",
				active.size(), failures.get(), directFailures.get(), hashtagFailures.get(),
				(System.nanoTime() - startNanos) / 1_000_000);
	}

	/**
	 * 브랜드 1건의 3단계 — 유저태그 스윕·direct 2단계·해시태그 스윕은 각자 try/catch로 격리한다
	 * (2026-08-18 direct 통합 §3-2). 한쪽 실패가 touchSwept·failures 카운트에 영향을 주지 않고, 어느 한
	 * 단계가 실패한 브랜드도 나머지 단계는 그대로 시도된다(서로 독립된 수집 경로 — 스펙 §8).
	 *
	 * <p><b>touchSwept는 1단계(유저태그) 성공에만 찍는다</b> — direct 2단계 실패가 계정을 "수집
	 * 준비 중"으로 되돌리면 안 된다(direct 실패는 그 게시물만의 문제이지 계정 전체의 문제가 아니다).
	 *
	 * <p>단계별 소요를 한 줄로 남긴다(2026-09-03) — 이번 병렬화 진단은 Loki 마커 3종으로 브랜드별
	 * 소요를 역산해야 했다. 다음 진단은 이 한 줄이면 된다. 이 메서드는 예외를 밖으로 내지 않는다
	 * (runSweep의 join()이 "전 브랜드 완료"를 뜻하는 전제).
	 */
	private void sweepOne(BrandRow b, LocalDate today, AtomicInteger failures,
			AtomicInteger directFailures, AtomicInteger hashtagFailures) {
		long t0 = System.nanoTime();
		try {
			collect.sweep(b);
			brands.touchSwept(b.id(), today);   // 성공 시에만 — 실패 브랜드는 "준비 중"으로 남는다
		} catch (RuntimeException e) {
			failures.incrementAndGet();
			log.warn("브랜드 스윕 실패(격리) — {}: {}", b.username(), e.toString());
		}
		long t1 = System.nanoTime();
		try {
			directCollect.sweepUnenumerated(b);
		} catch (RuntimeException e) {
			directFailures.incrementAndGet();
			log.warn("브랜드 direct 스윕 실패(격리) — {}: {}", b.username(), e.toString());
		}
		long t2 = System.nanoTime();
		try {
			hashtagCollect.sweep(b);
		} catch (RuntimeException e) {
			hashtagFailures.incrementAndGet();
			log.warn("브랜드 해시태그 스윕 실패(격리) — {}: {}", b.username(), e.toString());
		}
		long t3 = System.nanoTime();
		log.info("브랜드 스윕 완료 — {} {}ms (태그 {}ms · direct {}ms · 해시태그 {}ms)", b.username(),
				(t3 - t0) / 1_000_000, (t1 - t0) / 1_000_000, (t2 - t1) / 1_000_000, (t3 - t2) / 1_000_000);
	}
```

기존 `runSweep`의 javadoc(격리·touchSwept 설명)은 `sweepOne`으로 옮겨졌으므로 원래 위치에서 지운다.

- [ ] **Step 5: `application.yml` 설정 키·주석**

`monitoring/src/main/resources/application.yml`의 `monitoring.brand` 블록에서 `backfill-concurrency` 줄 바로 아래에 추가:

```yaml
    sweep-concurrency: 4        # 야간 스윕 브랜드 단위 병렬 스레드(2026-09-03 설계) — 139브랜드 직렬 6시간 44분의 본체가
                                # Hiker 대기라 4병렬로 105~140분 기대. 전역 동시 콜 최악 17(워커 10+스윕 4+등록 core 2+
                                # 해시태그 1), 08-12 램프 실측 안전 구간(20) 안. 1이면 현행 직렬과 동일(롤백 손잡이)
```

같은 블록의 두 주석을 갱신:
- `enrich-executor-concurrency` 주석 "동시 보강 주체는 등록 2 + 스윕 1 = 최대 3" → "동시 보강 주체는 등록 2 + 스윕 4 = 최대 6".
- `backfill-concurrency` 주석 "전역 동시 콜 최악 13(워커 10+스윕 1+core 2)" → "전역 동시 콜 최악 17(워커 10+스윕 4+core 2+해시태그 1)".

- [ ] **Step 6: 통과 확인**

Run: `./gradlew :monitoring:test --tests "com.celfit.monitoring.service.BrandSweepJobTest"`
Expected: PASS (기존 전부 + 신규 4개).

- [ ] **Step 7: 커밋**

```bash
git add monitoring/src/main/java/com/celfit/monitoring/config/BrandBackfillConfig.java monitoring/src/main/java/com/celfit/monitoring/service/BrandSweepJob.java monitoring/src/main/resources/application.yml monitoring/src/test/java/com/celfit/monitoring/service/BrandSweepJobTest.java
git commit -m "feat(monitoring): 브랜드 태그 스윕 브랜드 단위 병렬화(brandSweepExecutor 4) + 전날 콜 수 LPT 배정 + 브랜드별 단계 소요 로그"
```

---

### Task 4: Hikari 풀 상향 · 문서 · 모듈 테스트 · PR

**Files:**
- Modify: `monitoring/src/main/resources/application.yml:4-7` (`spring.datasource.hikari`)
- Modify: `DECISIONS.md` (표 맨 위 행), `docs/tracks/MON-BT-브랜드-태그-모니터링.md` (`## 내용` 끝 문단 + `## 잔여 작업` 항목), `docs/superpowers/specs/2026-09-03-brand-sweep-parallel-design.md` (상태 헤더)
- Move: `docs/superpowers/plans/2026-09-03-brand-sweep-parallel.md` → `docs/superpowers/plans/archive/`

- [ ] **Step 1: Hikari 풀**

`application.yml` `spring.datasource` 아래(password 줄 다음)에 추가:

```yaml
    hikari:
      maximum-pool-size: 20   # 기본 10 → 20(2026-09-03 브랜드 스윕 병렬화) — 동시 DB 사용자 최악 스윕 4 + 보강 워커 10 +
                              # 광고 판정 워커 4 + 스케줄러 2 + 웹. 점유는 짧지만 10이면 풀 대기(30초 초과 시 예외 →
                              # 격리 실패로 집계)가 생긴다. 운영 Postgres max_connections 100 중 ~52 사용(09-03 실측)
```

- [ ] **Step 2: 모듈 전체 테스트**

Run: `./gradlew :monitoring:test`
Expected: BUILD SUCCESSFUL. 실패가 있으면 여기서 고치고 다시 돈다(Testcontainers — Docker Desktop 기동 확인).

- [ ] **Step 3: 문서**

(a) `DECISIONS.md` 표 맨 위에 행 추가:

```
| 2026-09-03 | **브랜드 태그 스윕 브랜드 단위 병렬화(동시성 4) — 6시간 44분 → 105~140분 기대** — 139브랜드 직렬 스윕이 8일 만에 4배(추세 유지 시 04:30 미러 침범). Loki 마커로 브랜드별 소요를 역산하니 직렬 합계 404분 중 상위 13개가 343분이고, 본체는 Hiker 대기(컨테이너 CPU 평균 0.03코어)라 브랜드 간 병렬이 정답. `brandSweepExecutor`(`monitoring.brand.sweep-concurrency:4`)에 브랜드 1건=태스크 1건, 전날 `brand_call_count` 내림차순 LPT 배정, 3단계 격리·touchSwept 불변. 동반: direct 2단계 겹침 가드를 서비스 전역 AtomicBoolean → 브랜드 키 집합(전역이면 병렬 브랜드가 서로의 2단계를 매일 건너뛴다), Hikari 풀 10→20, 전역 Hiker 동시 콜 예산 14→17(램프 실측 20 이내), 브랜드별 단계 소요 로그 신설. 롤백은 동시성 1. 스윕 뒤 아카이브 57분은 범위 밖(전체 잡 종료 KST 04:45~05:20). | [설계](docs/superpowers/specs/2026-09-03-brand-sweep-parallel-design.md) |
```

(b) `docs/tracks/MON-BT-브랜드-태그-모니터링.md` — `## 잔여 작업` 헤더 바로 앞(`## 내용`의 끝)에 문단 추가:

```
브랜드 단위 병렬 스윕(2026-09-03 — DECISIONS 09-03 행,
[spec 2026-09-03](../superpowers/specs/2026-09-03-brand-sweep-parallel-design.md)): 야간 스윕이
**6시간 44분**(139브랜드, 실패 0)까지 늘어 04:30 미러 침범이 예정돼 있었다. Loki 마커 역산으로
직렬 합계 404분 중 상위 13개가 343분(mom.twins 40.6·kayali 38.1·celimax.korea 37.6 …), 무거움은 ①열거
상한 2,000의 커서 96페이지(브랜드 안 병렬 불가) ②해시태그 세트 큰 브랜드의 2·3단계 단건 루프 두
종류. 컨테이너 CPU 평균 0.03코어 — 본체는 Hiker 대기. `BrandSweepJob`이 브랜드 1건=태스크 1건을
`brandSweepExecutor`(`monitoring.brand.sweep-concurrency:4`)에 제출하고 join, 제출 순서는 전날
`brand_call_count` 내림차순(`findActiveHeaviestFirst`, LPT). 브랜드 안 3단계 순서·격리·touchSwept
불변. 동반 수정 — direct 2단계 겹침 가드 `unenumeratedBusy`(서비스 전역)를 `busyBrands`(브랜드 키
집합)로: 전역이면 병렬 브랜드가 서로의 2단계를 "겹침"으로 건너뛴다. Hikari 풀 10→20, 전역 Hiker
동시 콜 예산 최악 14→17(08-12 램프 실측 20 이내, 힙 +30MB). 브랜드별 `브랜드 스윕 완료 — {u} {ms}ms
(태그·direct·해시태그)` 로그 신설. 기대 105~140분(KST 03:45~04:20 종료), 롤백은
`MONITORING_BRAND_SWEEP_CONCURRENCY=1`. **스윕 뒤 이미지 아카이브 57분(CPU 0.34~0.38코어)은 범위
밖** — 전체 잡 종료는 04:45~05:20.
```

`## 잔여 작업` 목록 끝에 항목 추가:

```
- **병렬 스윕 배포 후 실측(09-03)** — 첫 야간 스윕의 `브랜드 스윕 완료 —` 로그로 총 소요·브랜드별 꼬리를
  확인한다. 꼬리가 한 브랜드(40분급)에 묶이면 2안(브랜드 안 direct 단건 콜 워커 풀 팬아웃)을 얹는다.
  아카이브 57분을 앞당기려면 별도 설계(썸네일 아카이브 병렬화 또는 스윕과 분리).
```

(c) 스펙 상태 헤더 `> 상태: 🟢 활성` → `> 상태: ✅ 구현됨`. 계획 문서는 아카이브로 이동:

```bash
git mv docs/superpowers/plans/2026-09-03-brand-sweep-parallel.md docs/superpowers/plans/archive/
```

- [ ] **Step 4: 커밋 · PR**

```bash
git add -A monitoring/src/main/resources/application.yml DECISIONS.md docs/
git commit -m "docs: 브랜드 스윕 병렬화 결정 기록·트랙 갱신 + Hikari 풀 20 + 계획 아카이브"
git push -u origin feature/brand-tag-sweep-perf-fa8010
gh pr create --base develop --title "feat(monitoring): 브랜드 태그 스윕 브랜드 단위 병렬화(동시성 4)" --body-file <PR 본문 파일>
```

PR 본문에는 설계 §1 실측 표, 변경 4건(병렬 실행·busy 가드·LPT 조회·Hikari), 기대치·롤백(동시성 1), 범위 밖(아카이브 57분)을 적고 `🤖 Generated with [Claude Code](https://claude.com/claude-code)`로 끝낸다.
