# 브랜드 게시물 단위 정산 + ready 10초 상한 구현 계획

> 상태: 🟢 활성 · 스펙: [2026-08-14-brand-per-post-settlement-design.md](../specs/2026-08-14-brand-per-post-settlement-design.md)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 브랜드 태그 게시물의 보강 정산(`enriched_at`)을 페이지 배치(21건)에서 게시물 1건 단위로 바꾸고, FE ready(markServing)를 "첫 배치 완결 ∨ (10초 경과 ∧ 정산 ≥ 1건)"으로 연다.

**Architecture:** monitoring 모듈만 변경(3파일 + 설정 + 테스트 3파일). `TaggedPostRepository.markEnriched`를 COALESCE로 바꿔 이중 마킹을 무해화하고, `BrandCollectService.enrich`를 게시물당 퓨처 합성(게시자 퓨처는 게시자별 공유)으로 재구성해 게시물별로 정산 마킹 + 리스너 통지하며, `BrandRegistrationService.runBackfillSafely`에 10초 타이머(전용 스케줄러 빈)와 정산 리스너 기반 ready 판정을 얹는다. was는 무변경(`enriched_at IS NOT NULL` 게이트가 이미 게시물 단위다).

**Tech Stack:** Java 21, Spring Boot 4.1, CompletableFuture, JUnit 5 + AssertJ, Testcontainers(PostgreSQL — `BrandStoreTest`만).

## Global Constraints

- 주석·로그·커밋 메시지는 한국어, 커밋 prefix `feat(monitoring):`/`test(monitoring):`/`docs:` 식 (CLAUDE.md)
- 테스트는 모듈 단위: `./gradlew :monitoring:test` (전체 `./gradlew test`는 PR 직전에만)
- `BrandStoreTest`는 Testcontainers — 이 머신은 Docker Desktop이 정본이라 `DOCKER_HOST`를 **설정하지 않는다** (08-09 확인, CLAUDE.md의 colima 서술은 이 머신엔 해당 없음)
- 스키마 마이그레이션 없음 — 새 Flyway 파일을 만들지 않는다
- 기존 동작 계약 유지: touchSwept은 전 페이지 보강 뒤(FE 폴링 종료 조건), 태그 0건 브랜드는 루프 종료 시 markServing, 보강 실패도 정산(시도 종료 = 정산), enrich 전체 join 유지(브랜드 간 백프레셔)
- 들여쓰기는 탭(기존 코드 관용) — 이 레포 Java 파일은 탭 인덴트다

---

### Task 1: `markEnriched`를 COALESCE로 — 최초 정산 시각 보존

**Files:**
- Modify: `monitoring/src/main/java/com/celfit/monitoring/store/TaggedPostRepository.java:135` (markEnriched의 UPDATE SQL)
- Test: `monitoring/src/test/java/com/celfit/monitoring/store/BrandStoreTest.java` (기존 `markEnriched는_지정_브랜드의_지정_코드에만_정산_시각을_찍는다` 근처에 추가)

**Interfaces:**
- Consumes: 기존 `markEnriched(long brandId, Collection<String> codes, Instant at)` — 시그니처 불변
- Produces: 이중 마킹 시 최초 시각 보존 의미 — Task 2의 "게시물별 마킹 + finally 안전판" 공존이 이 의미에 기댄다

- [ ] **Step 1: 실패하는 테스트 작성**

`BrandStoreTest.java`의 기존 markEnriched 테스트 아래에 추가(같은 헬퍼 `enrichedAt(id, code)`·브랜드 시드 관용구 재사용 — 기존 테스트 본문을 보고 동일하게 시드한다):

```java
/**
 * 이중 마킹은 최초 정산 시각을 보존한다(2026-08-14 게시물 단위 정산 스펙 §3) — 게시물별 마킹과
 * finally 안전판, 그리고 매일 스윕의 재보강이 같은 행을 다시 마킹해도 enriched_at이 뒤로 밀리지
 * 않는다. 소비자는 was 게이트(IS NOT NULL)뿐이라 동작 차이는 없지만 의미를 "최초 정산"으로 고정한다.
 */
@Test
void 이중_마킹은_최초_정산_시각을_보존한다() {
	long id = seedBrand("brandx");   // 기존 markEnriched 테스트와 같은 시드 헬퍼 사용
	taggedPosts.insert(id, post("A"));
	Instant first = Instant.parse("2026-08-14T00:00:00Z");
	Instant later = Instant.parse("2026-08-14T01:00:00Z");

	taggedPosts.markEnriched(id, List.of("A"), first);
	taggedPosts.markEnriched(id, List.of("A"), later);

	assertThat(enrichedAt(id, "A")).isEqualTo(first);   // 두 번째 마킹이 덮지 않는다
}
```

주의: `seedBrand`·`post`·`enrichedAt` 헬퍼의 실제 이름·시그니처는 기존 `markEnriched는_지정_브랜드의_지정_코드에만_정산_시각을_찍는다` 테스트(BrandStoreTest.java:462 부근)를 열어 그대로 따른다 — 위 코드는 형태 예시고, 기존 테스트가 쓰는 시드 방식을 복제하는 것이 정답이다.

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :monitoring:test --tests "com.celfit.monitoring.store.BrandStoreTest"`
Expected: 새 테스트만 FAIL — `expected: 2026-08-14T00:00:00Z but was: 2026-08-14T01:00:00Z`

- [ ] **Step 3: SQL 수정**

`TaggedPostRepository.markEnriched`의 UPDATE 한 줄:

```java
db.update("UPDATE brand_tagged_post SET enriched_at = COALESCE(enriched_at, ?) WHERE brand_id = ?"
		+ " AND short_code IN (" + placeholders + ")", args);
```

메서드 javadoc(없으면 신설)에 한 줄: `이중 마킹은 최초 시각을 보존한다(COALESCE) — 게시물별 마킹·finally 안전판·스윕 재보강이 겹쳐도 안전.`

- [ ] **Step 4: 통과 확인**

Run: `./gradlew :monitoring:test --tests "com.celfit.monitoring.store.BrandStoreTest"`
Expected: 전부 PASS (기존 테스트 포함 — 기존 테스트는 미정산 행에 1회 마킹이라 COALESCE 영향 없음)

- [ ] **Step 5: 커밋**

```bash
git add monitoring/src/main/java/com/celfit/monitoring/store/TaggedPostRepository.java monitoring/src/test/java/com/celfit/monitoring/store/BrandStoreTest.java
git commit -m "feat(monitoring): markEnriched를 COALESCE로 — 이중 마킹이 최초 정산 시각을 보존"
```

---

### Task 2: `BrandCollectService.enrich` — 게시물 단위 정산 + 정산 리스너

**Files:**
- Modify: `monitoring/src/main/java/com/celfit/monitoring/service/BrandCollectService.java:276-305` (enrich), `:414-436` (ensureAuthors → submitAuthorTasks), `:470-501` (collectCommentsGated → submitCommentTasks)
- Test: `monitoring/src/test/java/com/celfit/monitoring/service/BrandCollectServiceTest.java`

**Interfaces:**
- Consumes: Task 1의 markEnriched COALESCE 의미(게시물별 마킹과 finally 안전판이 겹쳐도 최초 시각 보존)
- Produces: `public void enrich(BrandRow brand, List<PostInfo> posts, Consumer<String> onPostSettled)` — 게시물 1건의 정산 마킹 **성공 직후** 그 shortCode로 1회 호출된다(호출 스레드는 enrich 워커 또는 enrich 호출 스레드). 기존 `enrich(BrandRow, List<PostInfo>)`는 무리스너 위임으로 유지. Task 3의 ready 판정이 이 리스너를 소비한다.

- [ ] **Step 1: 실패하는 테스트 작성 — 정산 배치 관측 지점 추가 + 신규 테스트 4개**

`BrandCollectServiceTest.InMemoryTagged`에 마킹 호출 단위 관측을 추가한다(기존 `enriched` 평탄 리스트는 기존 단언 호환을 위해 유지):

```java
// InMemoryTagged 필드 추가 (기존 enriched 아래)
/** markEnriched 호출 1번 = 원소 1개 — 정산이 게시물 단위인지(1건 배치) 페이지 배치인지 구분한다. */
final List<List<String>> enrichedBatches = Collections.synchronizedList(new ArrayList<>());
```

```java
// InMemoryTagged.markEnriched 오버라이드 수정 — 기존 본문에 한 줄 추가
@Override
public void markEnriched(long brandId, Collection<String> codes, Instant at) {
	if (markEnrichedFailsOnce) {
		markEnrichedFailsOnce = false;
		throw new IllegalStateException("정산 마킹 실패(DB 일시 오류)");
	}
	enriched.addAll(codes);
	enrichedBatches.add(List.copyOf(codes));
}
```

테스트 클래스 말미(기존 "보강 정산 마킹" 섹션)에 추가:

```java
// ── 게시물 단위 정산(2026-08-14 스펙 §1) ─────────────────────────────────

/**
 * 정산은 게시물 1건 단위로 각자 찍힌다 — 페이지 일괄 마킹이면 폴링이 21건 단위로만 점프하고,
 * 느린 게시자 콜 하나가 페이지 전체의 노출을 붙든다(직결 executor라 마킹 순서는 게시물 순서다).
 */
@Test
void 정산은_게시물_단위로_각자_찍힌다() {
	tagPages.add(page(null, reel("A", RECENT, 0, 101, ""), reel("B", RECENT, 0, 102, "")));

	service(2000).sweep(brand);

	assertThat(tagged.enrichedBatches).containsExactly(List.of("A"), List.of("B"));
}

/** 정산 리스너는 게시물이 정산될 때마다 그 코드로 불린다 — 등록 경로의 ready 판정 입력이다. */
@Test
void 정산_리스너는_정산된_게시물마다_불린다() {
	tagPages.add(page(null, reel("A", RECENT, 0, 101, ""), reel("B", RECENT, 0, 102, "")));
	BrandCollectService service = service(2000);
	List<PostInfo> posts = service.sweepCore(brand);
	List<String> settled = Collections.synchronizedList(new ArrayList<>());

	service.enrich(brand, posts, settled::add);

	assertThat(settled).containsExactly("A", "B");   // 직결 executor — 게시물 순서
}

/**
 * 배치 DB 조회 실패(finally 안전판 경로)에서는 리스너가 불리지 않는다 — 정산은 페이지 일괄로
 * 찍히지만(전량 백스톱) "게시물별 완결" 통지는 아니기 때문이다. ready 판정(등록 경로)은 이때
 * 첫 배치 완결 경로(페이지 태스크 완료)로만 열린다.
 */
@Test
void 배치_조회_실패의_안전판_정산은_리스너를_부르지_않는다() {
	authors.freshLookupFails = true;
	tagPages.add(page(null, reel("A", RECENT, 3, 101, ""), reel("B", RECENT, 3, 102, "")));
	BrandCollectService service = service(2000);
	List<PostInfo> posts = service.sweepCore(brand);
	List<String> settled = Collections.synchronizedList(new ArrayList<>());

	assertThatThrownBy(() -> service.enrich(brand, posts, settled::add))
			.isInstanceOf(IllegalStateException.class);

	assertThat(settled).isEmpty();                                        // 게시물별 완결 통지 없음
	assertThat(tagged.enriched).containsExactlyInAnyOrder("A", "B");      // 정산 자체는 전량(finally)
	assertThat(tagged.enrichedBatches).containsExactly(List.of("A", "B"));  // 일괄 1회
}

/**
 * 느린 게시자 콜이 다른 게시물의 정산을 막지 않는다 — 이 격리가 이번 전환의 목적이다
 * (배치 정산에서는 꼬리 콜 하나가 페이지 21건 전체의 노출을 붙들었다).
 */
@Test
void 느린_게시자가_다른_게시물의_정산을_막지_않는다() throws Exception {
	tagPages.add(page(null, reel("SLOW", RECENT, 0, 101, ""), reel("FAST", RECENT, 0, 102, "")));
	java.util.concurrent.CountDownLatch slowAuthor = new java.util.concurrent.CountDownLatch(1);
	HikerClient latched = new HikerClient(path -> {
		if (path.startsWith("/v2/user/by/username")) {
			return BRAND_PROFILE_JSON;
		}
		if (path.startsWith("/v2/user/tag/medias")) {
			return tagPages.get(0);
		}
		if (path.startsWith("/v2/user/by/id")) {
			String id = path.substring(path.indexOf("?id=") + "?id=".length());
			if (id.equals("101")) {
				try {
					slowAuthor.await(5, TimeUnit.SECONDS);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			}
			return "{\"user\":{\"pk\":%s,\"username\":\"author_%s\"}}".formatted(id, id);
		}
		throw new IllegalStateException("예상 밖 콜: " + path);
	});
	ExecutorService pool = Executors.newFixedThreadPool(2);
	try {
		BrandCollectService svc = new BrandCollectService(latched, callContext, writer, snapshots,
				comments, tagged, authors, pool, 2000, 3, 30);
		List<PostInfo> posts = svc.sweepCore(brand);
		List<String> settled = Collections.synchronizedList(new ArrayList<>());
		var run = java.util.concurrent.CompletableFuture.runAsync(
				() -> svc.enrich(brand, posts, settled::add));
		// FAST는 SLOW의 게시자 콜이 잡혀 있는 동안 정산돼야 한다 — 배치 정산이면 여기서 타임아웃.
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (!settled.contains("FAST") && System.nanoTime() < deadline) {
			Thread.sleep(10);
		}
		assertThat(settled).containsExactly("FAST");   // SLOW는 아직 미정산
		slowAuthor.countDown();
		run.get(5, TimeUnit.SECONDS);
		assertThat(settled).containsExactly("FAST", "SLOW");
	} finally {
		pool.shutdown();
	}
}
```

임포트 추가: `java.util.concurrent.CountDownLatch`는 위처럼 FQCN을 쓰거나 파일 상단 임포트에 추가(기존 스타일은 임포트 — `CyclicBarrier`가 이미 임포트돼 있는 블록에 나란히).

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :monitoring:test --tests "com.celfit.monitoring.service.BrandCollectServiceTest"`
Expected: 신규 4개 FAIL — `정산_리스너는…`·`배치_조회_실패…`는 컴파일 에러가 아니라 **3인자 enrich 부재로 컴파일 실패**가 먼저 난다. 컴파일부터 통과시키려면 Step 3과 함께 본다(TDD 관용상 여기선 "컴파일 실패 = 테스트 실패" 취급).

- [ ] **Step 3: enrich 재구성**

`BrandCollectService`에서 `enrich`·`ensureAuthors`·`collectCommentsGated`를 아래로 교체한다.
임포트 추가: `java.util.LinkedHashMap`, `java.util.concurrent.ConcurrentHashMap`, `java.util.function.Consumer`(이미 있음).

```java
/** 무리스너 위임 — 일일 스윕 경로. 동작은 3인자판과 동일하다(게시물 단위 정산 포함). */
public void enrich(BrandRow brand, List<PostInfo> posts) {
	enrich(brand, posts, code -> { });
}

/**
 * enrichment 단계(2026-08-14 게시물 단위 정산 개정) — core가 넘긴 편입 컷 안 게시물의 게시자
 * 프로필(미보유·30일 stale만) + 댓글 게이트. 구 2단계 배리어("게시자 전체 → 댓글 전체 → 페이지
 * 일괄 정산")를 게시물당 퓨처 합성으로 바꿨다: 게시물별로 자기 게시자·자기 댓글만 기다려
 * <b>1건씩 즉시 정산</b>하고 onPostSettled로 통지한다(등록 경로의 ready 판정 입력). 느린 콜
 * 하나가 페이지 전체의 노출을 붙들지 않는다. 게시자 퓨처는 게시자별 1개로 공유하므로 총 Hiker
 * 콜 수는 배리어 시절과 같다. 미수집분은 다음 스윕이 백스톱한다(게시자는 stale 판정, 댓글은
 * comments_collected_count 워터마크).
 *
 * <p>메서드 전체 join은 유지한다 — 호출자(등록 백필 core·스윕)가 페이지 완주까지 블로킹되는
 * 것이 유일한 브랜드 간 백프레셔다(08-13 설계, 없애면 08-12 OOM 형태 재현).
 *
 * <p><b>정산 마킹은 보강 성패와 무관하게 무조건 찍는다</b> — 게시물별 마킹이 본선이고, 바깥
 * finally는 그 마킹이 닿지 못한 잔여분(배치 DB 조회 실패·마킹 자체의 실패)의 일괄 백스톱이다.
 * 마킹을 놓치면 그 행은 enriched_at NULL로 남아 was 목록에 안 뜨는데, 180일 초과 게시물에는
 * 재열거 백스톱이 없어 그 미노출이 영구가 된다(상세 근거는 finally 블록 주석). 이중 마킹은
 * markEnriched의 COALESCE가 무해화한다(최초 정산 시각 보존).
 */
public void enrich(BrandRow brand, List<PostInfo> posts, Consumer<String> onPostSettled) {
	if (posts.isEmpty()) {
		return;
	}
	Set<String> settled = ConcurrentHashMap.newKeySet();
	try {
		// 배치 DB 조회 2건(stale 판정·댓글 워터마크)은 태스크 제출 전의 무방비 지점 — 여기서
		// 던지면 아래 finally가 페이지 전체를 일괄 정산한다(이미 제출된 게시자 태스크는 워커에서
		// 마저 돌아 프로필만 채우고 끝난다 — 정산·통지와 무관해 무해).
		Map<String, CompletableFuture<Void>> authorTasks = submitAuthorTasks(brand.id(), posts);
		Map<String, CompletableFuture<Void>> commentTasks = submitCommentTasks(brand.id(), posts);
		CompletableFuture<Void> done = CompletableFuture.completedFuture(null);
		List<CompletableFuture<Void>> perPost = new ArrayList<>();
		for (PostInfo p : posts) {
			CompletableFuture<Void> author = p.ownerUserId() == null ? done
					: authorTasks.getOrDefault(p.ownerUserId(), done);
			CompletableFuture<Void> comment = commentTasks.getOrDefault(p.shortCode(), done);
			// 게시자·댓글 태스크는 예외를 각자 삼키므로(격리 규칙) 이 합성은 정상 완료가 기본이고,
			// 여기서 새는 예외는 settlePost(정산 마킹)의 DB 실패뿐 — join이 모아 finally로 넘긴다.
			perPost.add(author.runAfterBoth(comment,
					() -> settlePost(brand.id(), p.shortCode(), settled, onPostSettled)));
		}
		CompletableFuture.allOf(perPost.toArray(CompletableFuture[]::new)).join();
	} finally {
		// 잔여분 일괄 정산(2026-08-13 스펙 §1의 finally 근거 그대로): 게시물별 마킹이 못 닿은 행을
		// 비운 채로 두면 실측 404 2%·타임아웃 1%가 아니라 "배치 조회 blip 한 번"으로 페이지 전체가
		// 영구 미노출이 된다(180일 초과 구간은 재열거 백스톱이 없다 — BrandCrawlPolicy.due 참조).
		List<String> remaining = posts.stream().map(PostInfo::shortCode)
				.filter(c -> !settled.contains(c)).toList();
		taggedPosts.markEnriched(brand.id(), remaining, Instant.now());
	}
	log.info("브랜드 태그 보강 — {} 게시자·댓글 수집 완료·정산({}건 대상)", brand.username(), posts.size());
}

/**
 * 게시물 1건 정산 — 마킹 성공 후에만 settled에 넣고 통지한다. 마킹이 던지면 settled에 안 남아
 * 바깥 finally의 잔여분 일괄 마킹이 백스톱한다. 리스너 실패는 격리한다 — ready 통지가 정산을
 * 되돌릴 이유가 없다(다음 게시물 정산이 재통지).
 */
private void settlePost(long brandId, String code, Set<String> settled, Consumer<String> onPostSettled) {
	taggedPosts.markEnriched(brandId, List.of(code), Instant.now());
	settled.add(code);
	try {
		onPostSettled.accept(code);
	} catch (RuntimeException e) {
		log.warn("게시물 정산 통지 실패(격리) — {}: {}", code, e.toString());
	}
}

/**
 * 게시자 프로필 태스크 제출 — 편입 컷 안 게시물 작성자 중 미보유·30일 경과(stale)만
 * /v2/user/by/id 1콜(스펙 §2·§8). 브랜드 간 전역 캐시(author_profile)라 같은 인플루언서를 여러
 * 브랜드가 태그해도 콜은 30일에 1번이다. <b>게시자별 퓨처 1개를 공유</b>한다 — 같은 작성자의
 * 게시물 여럿이 같은 퓨처를 기다리므로 게시물 단위 정산으로 바꿔도 콜 수는 늘지 않는다.
 * 게시자 단위 격리(fetchAuthorWithRetry가 삼킨다) — 한 명의 실패가 나머지에 번지지 않는다.
 * fresh(수집 불요) 게시자는 맵에 없다 — 소비자는 getOrDefault(완료 퓨처)로 접는다.
 */
private Map<String, CompletableFuture<Void>> submitAuthorTasks(long brandId, Collection<PostInfo> posts) {
	Set<String> ids = posts.stream().map(PostInfo::ownerUserId).filter(Objects::nonNull)
			.collect(Collectors.toCollection(LinkedHashSet::new));
	if (ids.isEmpty()) {
		return Map.of();
	}
	Set<String> fresh = authors.freshIgUserIds(ids,
			Instant.now().minus(Duration.ofDays(authorStaleDays)));
	// 태스크 본문은 runScoped로 다시 감싼다 — 콜 집계의 브랜드 컨텍스트(ThreadLocal)는 워커
	// 스레드로 넘어가지 않기 때문(BrandCallContext 주석 참조).
	Map<String, CompletableFuture<Void>> tasks = new LinkedHashMap<>();
	for (String id : ids) {
		if (fresh.contains(id)) {
			continue;
		}
		tasks.put(id, CompletableFuture.runAsync(() -> callContext.runScoped(brandId,
				() -> fetchAuthorWithRetry(id)), enrichWorker));
	}
	return tasks;
}

/**
 * 댓글 태스크 제출 — 댓글 게이트(스펙 §2)는 그대로다: 열거 comment_count가 저장값보다 클 때만
 * /v2/media/comments 최대 commentPages콜, 기지 댓글 페이지에서 중단, 미완주면 워터마크 유지.
 * 게이트에 안 걸린 게시물은 맵에 없다(= 댓글 대기 없이 게시자만 끝나면 정산). 게시물 단위 격리
 * (태스크 안 try/catch) — 한 게시물의 실패가 나머지에 번지지 않는다.
 */
private Map<String, CompletableFuture<Void>> submitCommentTasks(long brandId, Collection<PostInfo> posts) {
	List<PostInfo> candidates = posts.stream().filter(p -> p.comments() != null).toList();
	if (candidates.isEmpty()) {
		return Map.of();
	}
	Map<String, Long> stored = taggedPosts.commentsCollectedCounts(brandId,
			candidates.stream().map(PostInfo::shortCode).toList());
	Map<String, CompletableFuture<Void>> tasks = new LinkedHashMap<>();
	for (PostInfo p : candidates) {
		if (p.comments() <= stored.getOrDefault(p.shortCode(), 0L)) {
			continue;
		}
		tasks.put(p.shortCode(), CompletableFuture.runAsync(() -> callContext.runScoped(brandId, () -> {
			try {
				HikerClient.CommentsFetch fetch = hiker.fetchComments(p.shortCode(), p.username(),
						commentPages, comments.findIds(p.shortCode()));
				comments.upsertForPost(p.shortCode(), fetch.comments());
				// 저장값은 열거 관측치로 갱신한다 — 다음 게이트가 "그 사이 증가분"만 보게.
				// 단, 미완주(중간 페이지 실패)면 유지한다 — 워터마크를 올리면 다음 스윕 게이트가
				// 닫혀 못 받은 페이지가 영영 빈다(받은 부분은 위 upsert로 이미 보존됐다).
				if (fetch.complete()) {
					taggedPosts.updateCommentsCollected(brandId, p.shortCode(), p.comments());
				}
			} catch (RuntimeException e) {
				log.warn("태그 댓글 수집 실패(격리) — 게시물 {}: {}", p.shortCode(), e.toString());
			}
		}), enrichWorker));
	}
	return tasks;
}
```

삭제: 기존 `ensureAuthors`·`collectCommentsGated` 메서드(위 submit 2종이 대체). `fetchAuthorWithRetry`는 그대로 둔다.

클래스 상단 javadoc(2026-08-13 언급부)에 한 줄 보태 개정을 반영한다: "2026-08-14 게시물 단위 정산 개정: 방출 단위가 페이지 배치에서 게시물 1건으로 좁혀졌다 — enrich 참조."

`enrichSafely`·`sweep`은 무수정(2인자 enrich가 3인자로 위임하므로 스윕 경로도 자동으로 게시물 단위 정산).

- [ ] **Step 4: 통과 확인**

Run: `./gradlew :monitoring:test --tests "com.celfit.monitoring.service.BrandCollectServiceTest"`
Expected: 전부 PASS. 특히 기존 테스트 중 정산 관련(`보강이_끝나면_정산_마킹한다`, `게시자_수집이_실패해도_정산한다`, `댓글_미완주여도_정산하되_워터마크는_전진하지_않는다`, `게시자_stale_배치_조회가_던져도_정산한다`, `댓글_워터마크_배치_조회가_던져도_정산한다`, `보강_게시자_콜은_워커_풀_동시성으로_나가되_상한을_넘지_않는다`)이 전부 그대로 PASS여야 한다 — 이들이 "의미 불변" 회귀 그물이다.

주의(예상 조정 1건): `댓글_워터마크_배치_조회가_던져도_정산한다`는 현행 코드에서 게시자 단계가 **끝난 뒤** 댓글 배치 조회가 던졌다. 새 구조에서는 게시자 태스크 제출(비동기) 직후 댓글 배치 조회가 던지므로, 직결 executor(`Runnable::run`)에서는 게시자 콜이 제출 시점에 완료돼 `authors.upserted` 단언이 그대로 성립한다. 만약 이 단언이 깨지면 원인은 제출 순서 변경이니 테스트 의도(게시자 단계 통과 후 두 번째 배치 조회 지점 검증)를 지키는 선에서 단언을 조정하되, `tagged.enriched` 정산 단언은 절대 약화하지 않는다.

- [ ] **Step 5: 커밋**

```bash
git add monitoring/src/main/java/com/celfit/monitoring/service/BrandCollectService.java monitoring/src/test/java/com/celfit/monitoring/service/BrandCollectServiceTest.java
git commit -m "feat(monitoring): 보강 정산을 게시물 단위로 — 게시물별 퓨처 합성 + 정산 리스너"
```

---

### Task 3: ready 판정 개편 — 10초 타이머 + 정산 리스너

**Files:**
- Modify: `monitoring/src/main/java/com/celfit/monitoring/config/BrandBackfillConfig.java` (brandServingTimer 빈 추가 + 클래스 javadoc의 ready 서술 갱신)
- Modify: `monitoring/src/main/java/com/celfit/monitoring/service/BrandRegistrationService.java` (생성자 + runBackfillSafely + runEnrichSafely + 클래스·메서드 javadoc)
- Modify: `monitoring/src/main/resources/application.yml` (monitoring.brand.serving-open-timeout 추가)
- Test: `monitoring/src/test/java/com/celfit/monitoring/service/BrandRegistrationServiceTest.java`

**Interfaces:**
- Consumes: Task 2의 `enrich(BrandRow, List<PostInfo>, Consumer<String>)` — 게시물 정산 성공마다 shortCode로 1회 호출
- Produces: `BrandRegistrationService` 생성자 시그니처 변경 — 기존 8개 인자 뒤에 `@Qualifier("brandServingTimer") ScheduledExecutorService servingTimer`, `@Value("${monitoring.brand.serving-open-timeout:10s}") Duration servingOpenTimeout` 추가. 새 빈 `brandServingTimer`(ScheduledExecutorService, 데몬 단일 스레드).

- [ ] **Step 1: 실패하는 테스트 작성**

`BrandRegistrationServiceTest`를 다음과 같이 고친다.

**(a) StubCollect — 3인자 enrich 오버라이드로 교체.** 실서비스가 3인자판을 호출하므로 2인자 오버라이드는 더 이상 안 불린다. 기존 2인자 오버라이드를 지우고 아래로 대체(기록 필드·지연·실패 주입 의미는 그대로, 게시물별 리스너 통지가 추가):

```java
/** 게시물 정산 직전 훅 — 테스트가 타이머 발화를 정산 사이에 끼워 넣는 지점(기본 no-op). */
Runnable beforeSettle = () -> { };
/** 지금까지 "정산 통지"가 나간 게시물 코드 — markServing·touchSwept 시점 스냅샷의 원천. */
final List<String> settledCodes = new CopyOnWriteArrayList<>();

@Override
public void enrich(BrandRow brand, List<PostInfo> posts,
		java.util.function.Consumer<String> onPostSettled) {
	if (enrichFailing.contains(brand.username())) {
		throw new IllegalStateException("보강 실패 주입");
	}
	sleep(enrichDelay);
	enriched.add(brand.username());
	enrichedPosts.add(posts.stream().map(PostInfo::shortCode).toList());
	// 실코드의 게시물 단위 정산 재현 — 1건 정산할 때마다 리스너 통지(Task 2 계약).
	for (PostInfo p : posts) {
		beforeSettle.run();
		settledCodes.add(p.shortCode());
		onPostSettled.accept(p.shortCode());
	}
	callOrder.add("enrich");
}

/** 지금까지 보강(정산)이 끝난 게시물 코드 전부 — markServing·touchSwept 시점 스냅샷용. */
List<String> enrichedCodes() {
	return List.copyOf(settledCodes);
}
```

(기존 `enrichedCodes()`는 `enrichedPosts` 평탄화였다 — `settledCodes` 기반으로 바꾸면 "markServing 시점에 몇 건이 정산돼 있었나"가 게시물 단위로 관측된다. 기존 테스트 `첫_페이지_배치_보강_후에_markServing을_1회_부른다`의 스냅샷 단언 `List.of("P1_A", "P1_B")`는 그대로 성립한다 — 첫 페이지 2건이 모두 정산된 뒤 페이지 태스크가 markServing을 부르기 때문.)

**(b) 타이머 대역 + service() 헬퍼 갱신.** 테스트 필드에 추가:

```java
/**
 * 타이머 대역 — schedule을 가로채 태스크만 붙잡는다(자동 실행 없음). 테스트가 fire()로 발화
 * 시점을 정산 사이에 결정적으로 끼워 넣는다. 반환 null은 안전하다 — 프로덕션 코드는 반환
 * ScheduledFuture를 쓰지 않는다(취소 불필요: 만료 태스크는 served CAS 가드의 no-op 체크뿐).
 */
private static final class CapturingTimer extends java.util.concurrent.ScheduledThreadPoolExecutor {
	Runnable task;
	long delayMs = -1;

	CapturingTimer() {
		super(1, r -> {
			Thread t = new Thread(r, "test-serving-timer");
			t.setDaemon(true);
			return t;
		});
	}

	@Override
	public java.util.concurrent.ScheduledFuture<?> schedule(Runnable command, long delay,
			java.util.concurrent.TimeUnit unit) {
		this.task = command;
		this.delayMs = unit.toMillis(delay);
		return null;
	}

	void fire() {
		task.run();
	}
}

private final CapturingTimer servingTimer = new CapturingTimer();
```

`service()` 헬퍼의 생성자 호출을 갱신:

```java
return new BrandRegistrationService(hiker, brands, collect, callCounts,
		hashtags, hashtagCollect, Runnable::run, enrich, servingTimer, Duration.ofSeconds(10));
```

`tearDown`에 `servingTimer.shutdownNow();` 한 줄 추가.

**(c) 신규 테스트 3개** (기존 markServing 테스트들 옆에):

```java
/**
 * 10초 타이머가 만료됐고 정산이 1건 이상이면 첫 배치 완결을 기다리지 않고 ready를 연다
 * (2026-08-14 스펙 §2) — 타이머가 정산 도착 전에 만료된 경우, 그 뒤 첫 정산이 여는 쪽.
 */
@Test
void 타이머_만료_후_첫_정산이_ready를_연다() {
	twoPages();
	// 첫 게시물 정산 직전에 타이머 만료 — 그 시점 정산 0건이라 타이머 자신은 열지 못한다.
	collect.beforeSettle = () -> {
		if (servingTimer.task != null && brands.served.isEmpty()) {
			servingTimer.fire();
			assertThat(brands.served).isEmpty();   // 정산 0건 — 타이머만으로는 안 연다(빈 ready 금지)
		}
	};

	var result = service().register("brandx");
	awaitEnrich();

	assertThat(brands.served).containsExactly(result.brandId());   // 1회뿐
	// 첫 정산 1건(P1_A) 시점에 열렸다 — 첫 배치(P1_A+P1_B) 완결 시점이 아니다.
	assertThat(brands.enrichedAtServingMark).containsExactly(List.of("P1_A"));
}

/** 타이머 만료 시점에 이미 정산이 있으면 만료 즉시 연다 — 만료가 정산 뒤에 온 경우. */
@Test
void 타이머_만료_시_정산이_있으면_즉시_ready를_연다() {
	twoPages();
	// 두 번째 게시물 정산 직전 = 첫 게시물(P1_A)은 정산 완료 상태에서 타이머 만료.
	collect.beforeSettle = () -> {
		if (servingTimer.task != null && collect.settledCodes.size() == 1 && brands.served.isEmpty()) {
			servingTimer.fire();
			assertThat(brands.served).hasSize(1);   // 만료 즉시 열렸다(다음 정산을 안 기다림)
		}
	};

	var result = service().register("brandx");
	awaitEnrich();

	assertThat(brands.served).containsExactly(result.brandId());
	assertThat(brands.enrichedAtServingMark).containsExactly(List.of("P1_A"));
}

/** 타이머는 등록 백필 시작 시점에 설정값(10s)으로 1회 예약된다. */
@Test
void 타이머는_백필_시작_시_10초로_예약된다() {
	service().register("brandx");

	assertThat(servingTimer.delayMs).isEqualTo(10_000L);
}
```

**(d) 기존 테스트 컴파일 수정** — `service()` 시그니처 변경 외에 기존 테스트 본문은 무수정이어야 한다. 특히:
- `첫_페이지_배치_보강_후에_markServing을_1회_부른다` — 타이머 미발화(기본)면 첫 배치 완결 경로 그대로 → 스냅샷 `List.of("P1_A", "P1_B")` 유지. **이 테스트가 "10초 전 첫 배치 완결 → 즉시 ready" 회귀 그물이다.**
- `태그가_없어도_markServing으로_ready를_연다` — 빈 페이지 콜백 → enrich no-op(posts empty) → 페이지 태스크의 첫 배치 완결 경로가 연다. 정산 ≥ 1건 조건은 타이머 경로에만 붙는다.

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :monitoring:test --tests "com.celfit.monitoring.service.BrandRegistrationServiceTest"`
Expected: 컴파일 실패(생성자 인자 8→10, StubCollect의 3인자 enrich 부재) — Step 3 후 신규 3개가 의미 있게 검증된다.

- [ ] **Step 3: 프로덕션 코드**

**(a) `BrandBackfillConfig`** — 빈 추가(파일 말미) + 클래스 javadoc의 "ready는 첫 페이지 배치의 보강 완료 시점" 문장을 "ready는 첫 배치 완결 또는 (serving-open-timeout 경과 후 정산 1건 이상) 중 빠른 쪽(2026-08-14 개정)"으로 갱신:

```java
/**
 * ready 개방 타이머(2026-08-14 게시물 단위 정산 스펙 §2) — 등록 백필이 첫 배치 완결을 최대
 * serving-open-timeout(기본 10초)까지만 기다리게 하는 단발 타이머 전용. enrich executor에
 * 태우지 않는 이유: 포화 시 큐 대기가 10초 약속을 깨뜨린다. 태스크는 가드된 no-op 체크
 * 수준(마킹 UPDATE 1건 최대)이라 단일 스레드로 충분하다.
 */
@Bean(name = "brandServingTimer")
public java.util.concurrent.ScheduledExecutorService brandServingTimer() {
	return java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "brand-serving-timer");
		t.setDaemon(true);
		return t;
	});
}
```

(임포트 정리: `ScheduledExecutorService`를 파일 상단 임포트로 올려도 된다 — 기존 스타일 따름.)

**(b) `application.yml`** — `monitoring.brand` 블록(backfill-concurrency 아래)에:

```yaml
    serving-open-timeout: 10s   # 등록 ready(markServing) 대기 상한(2026-08-14) — 첫 배치(21건) 완결이 이보다
                                # 늦으면 "타이머 만료 + 정산 1건 이상" 시점에 되는 만큼만 연다(빈 ready는 안 연다)
```

**(c) `BrandRegistrationService`** — 필드·생성자에 추가:

```java
private final ScheduledExecutorService servingTimer;
private final Duration servingOpenTimeout;
```

```java
public BrandRegistrationService(HikerClient hiker, BrandRepository brands,
		BrandCollectService collect, BrandCallCountRepository callCounts,
		BrandHashtagRepository hashtags, BrandHashtagCollectService hashtagCollect,
		@Qualifier("brandBackfillExecutor") Executor backfill,
		@Qualifier("brandEnrichExecutor") Executor enrich,
		@Qualifier("brandServingTimer") ScheduledExecutorService servingTimer,
		@Value("${monitoring.brand.serving-open-timeout:10s}") Duration servingOpenTimeout) {
	// ... 기존 대입 + 신규 2개
}
```

임포트: `java.time.Duration`, `java.util.concurrent.ScheduledExecutorService`, `java.util.concurrent.TimeUnit`, `java.util.function.Consumer`, `org.springframework.beans.factory.annotation.Value`.

`runBackfillSafely`·`runEnrichSafely` 교체:

```java
/**
 * (기존 javadoc의 파이프라인·backpressure·touchSwept 서술 유지 — "첫 제출분의 보강이 끝나는
 * 지점에서 markServing" 문장만 아래로 교체)
 *
 * <p>ready(markServing)는 <b>둘 중 먼저 오는 쪽</b>이 연다(2026-08-14 게시물 단위 정산 스펙 §2):
 * ① 첫 페이지 배치 완결(10초 전에 다 끝나는 정상 경로 — 현행 유지), ② serving-open-timeout
 * (기본 10초) 만료 이후의 <b>정산 1건 이상</b> — 만료 시점에 이미 있으면 만료 즉시, 없으면 그 뒤
 * 첫 정산이 도착하는 순간. 빈 ready는 열지 않는다(사용자 결정 — Hiker가 느린 날 빈 화면 깜빡임
 * 방지). 태그 0건 브랜드는 예외로 루프 종료 시점에 연다(정산할 것이 없다 — collecting 영구 갇힘
 * 방지, 현행 유지). 타이머 반환 퓨처는 취소하지 않는다 — 만료 태스크는 served CAS 가드라 백필
 * 완주 뒤에 발화해도 no-op이다.
 */
private void runBackfillSafely(BrandRow row) {
	try {
		AtomicBoolean served = new AtomicBoolean();
		AtomicBoolean timerFired = new AtomicBoolean();
		AtomicBoolean anySettled = new AtomicBoolean();
		// 타이머 경로의 개방 판정 — 만료 전엔 절대 안 열고(첫 배치 완결 경로가 전담), 만료 후엔
		// 정산 1건 이상일 때만 연다. 정산 통지·타이머 발화 양쪽에서 불려 어느 쪽이 늦든 잡는다.
		Runnable openIfTimedOut = () -> {
			if (timerFired.get() && anySettled.get() && served.compareAndSet(false, true)) {
				brands.markServing(row.id());
			}
		};
		servingTimer.schedule(() -> {
			timerFired.set(true);
			openIfTimedOut.run();
		}, servingOpenTimeout.toMillis(), TimeUnit.MILLISECONDS);
		List<CompletableFuture<Void>> pages = new ArrayList<>();
		collect.sweepCore(row, page -> pages.add(CompletableFuture.runAsync(() -> {
			runEnrichSafely(row, page, code -> {
				anySettled.set(true);
				openIfTimedOut.run();
			});
			// 첫 페이지 배치 완결 — 타이머와 무관하게 여는 정상 경로. 페이지 순서가 아니라 완료
			// 순서인 것은 무해하다 — 목록 정렬은 taken_at이고 markServing은 last_swept_at IS NULL
			// 가드로 1회만 먹는다(served CAS는 타이머 경로와의 프로세스 내 이중 방어).
			if (served.compareAndSet(false, true)) {
				brands.markServing(row.id());
			}
		}, enrich)));
		CompletableFuture.allOf(pages.toArray(CompletableFuture[]::new)).join();
		brands.touchSwept(row.id(), LocalDate.now(KST));
		enrich.execute(() -> runHashtagBackfillSafely(row));
	} catch (RuntimeException e) {
		log.warn("브랜드 등록 백필 실패(격리) — {} 다음 스윕이 백스톱: {}", row.username(), e.toString());
		// was 폴링 계약(§5-2) — collecting에서 빠져나올 신호. 다음 스윕 성공(touchSwept)이 클리어한다.
		// markServing 이후 실패면 ready가 이미 열려 있고(정산분 서빙) 이 문구는 FE에서 무시된다.
		brands.markBackfillError(row.id(), "초기 수집에 실패했어요. 자동으로 재시도 중이에요.");
	}
}

/**
 * 보강 실패는 backfill_error를 남기지 않는다 — 목록·지표는 이미 서빙 중(ready)이라 "초기 수집
 * 실패" 문구가 오히려 오보고, 미수집분(게시자 stale·댓글 워터마크)은 다음 스윕이 자동 재시도한다.
 */
private void runEnrichSafely(BrandRow row, List<PostInfo> posts, Consumer<String> onPostSettled) {
	try {
		collect.enrich(row, posts, onPostSettled);
	} catch (RuntimeException e) {
		log.warn("브랜드 등록 보강 실패(격리) — {} 다음 스윕이 백스톱: {}", row.username(), e.toString());
	}
}
```

클래스 상단 javadoc의 "ready(markServing)는 첫 페이지분 보강이 끝나는 지점에서 열리고" 문장도 같은 규칙으로 갱신한다.

- [ ] **Step 4: 통과 확인**

Run: `./gradlew :monitoring:test --tests "com.celfit.monitoring.service.BrandRegistrationServiceTest"`
Expected: 전부 PASS — 신규 3개 + 기존 전체(특히 `첫_페이지_배치_보강_후에_markServing을_1회_부른다`, `태그가_없어도_markServing으로_ready를_연다`, `모든_페이지_보강이_끝난_뒤에_touchSwept한다`).

- [ ] **Step 5: monitoring 모듈 전체 테스트**

Run: `./gradlew :monitoring:test`
Expected: 전부 PASS (Spring 컨텍스트를 띄우는 테스트가 있으면 새 빈·설정 키가 함께 검증된다)

- [ ] **Step 6: 커밋**

```bash
git add monitoring/src/main/java/com/celfit/monitoring/config/BrandBackfillConfig.java monitoring/src/main/java/com/celfit/monitoring/service/BrandRegistrationService.java monitoring/src/main/resources/application.yml monitoring/src/test/java/com/celfit/monitoring/service/BrandRegistrationServiceTest.java
git commit -m "feat(monitoring): ready를 '첫 배치 완결 ∨ (10초 경과 ∧ 정산 1건+)'으로 — 등록 대기 상한"
```

---

### Task 4: 문서 갱신 + PR

**Files:**
- Modify: `DECISIONS.md` (맨 위에 새 결정 추가)
- Modify: `docs/tracks/MON-BT-브랜드-태그-모니터링.md` (상태 갱신 — 파일을 열어 기존 서술 형식을 따른다)
- Modify: `docs/superpowers/specs/2026-08-13-brand-initial-batch-serving-design.md` (상태 헤더만 — 내용 불변 원칙)
- Modify: `docs/superpowers/plans/2026-08-14-brand-per-post-settlement.md` → `docs/superpowers/plans/archive/`로 이동
- Modify: `docs/superpowers/specs/2026-08-14-brand-per-post-settlement-design.md` (상태 헤더에 `✅ 구현됨` 추가)

**Interfaces:**
- Consumes: Task 1~3 완료 상태
- Produces: develop 대상 PR

- [ ] **Step 1: DECISIONS.md 맨 위에 결정 기록**

기존 항목 형식을 확인하고 같은 형식으로, 요지는:

> **2026-08-14 — 브랜드 보강 정산을 게시물 단위로, 등록 ready 대기 상한 10초.**
> 방출 단위를 페이지 배치(21건)에서 게시물 1건으로 좁혔다(08-13 결정의 "게시물 1건 단위 방출" 기각을 사용자 요청으로 뒤집음 — 운영 체감 첫 ready ~30초, 꼬리 콜이 배치 전체를 붙드는 구조적 약점). markEnriched는 COALESCE(최초 정산 시각 보존), 게시자 퓨처는 게시자별 공유라 콜 수 불변. ready는 "첫 배치 완결 ∨ (serving-open-timeout 10초 경과 ∧ 정산 ≥ 1건)" — 빈 ready는 열지 않는다(사용자 결정). touchSwept(=collectionCompletedAt)·태그 0건 규칙·시도 종료 시 정산·백프레셔(전체 join)는 불변. 스펙: specs/2026-08-14-brand-per-post-settlement-design.md

- [ ] **Step 2: 트랙 문서·스펙 상태 헤더 갱신**

- `docs/tracks/MON-BT-브랜드-태그-모니터링.md`: 최근 상태 항목에 이번 변경(게시물 단위 정산 + ready 10초 상한) 한 줄 추가 — 파일의 기존 형식을 따른다.
- `2026-08-13-...-design.md` 상태 헤더에 추가: `· 방출 단위(§2)는 2026-08-14 게시물 단위 정산 스펙이 대체`
- `2026-08-14-...-design.md` 상태 헤더를 `> 상태: 🟢 활성 · ✅ 구현됨(2026-08-14) · 구현 계획: ...plans/archive/2026-08-14-brand-per-post-settlement.md`로 갱신

- [ ] **Step 3: 계획 문서 아카이브**

```bash
git mv docs/superpowers/plans/2026-08-14-brand-per-post-settlement.md docs/superpowers/plans/archive/
```

- [ ] **Step 4: 커밋 + PR**

```bash
git add -A
git commit -m "docs: 게시물 단위 정산·ready 10초 상한 반영 — DECISIONS·트랙·스펙 갱신, 계획 아카이브"
git push -u origin feature/brand-monitoring-request-timing-6fea30
gh pr create --base develop --title "feat(monitoring): 브랜드 보강 정산 게시물 단위 전환 + 등록 ready 10초 상한" --body "$(cat <<'EOF'
## 요약
- 보강 정산(enriched_at)을 페이지 배치(21건)에서 **게시물 1건 단위**로 — 폴링마다 완결된 게시물부터 즉시 노출(느린 꼬리 콜이 페이지 전체를 붙들지 않음)
- 등록 ready(markServing)를 **"첫 배치 완결 ∨ (10초 경과 ∧ 정산 ≥ 1건)"**으로 — 운영 체감 ~30초 대기를 상한 10초로(빈 ready는 열지 않음)
- markEnriched를 COALESCE로(최초 정산 시각 보존), 게시자 퓨처 공유로 Hiker 콜 수 불변, touchSwept·태그 0건 규칙·백프레셔 불변
- was 무변경(enriched_at 게이트가 이미 게시물 단위), 스키마 마이그레이션 없음

스펙: docs/superpowers/specs/2026-08-14-brand-per-post-settlement-design.md

## 테스트
- `./gradlew :monitoring:test` 전체 통과
- 신규: 게시물 단위 정산·정산 리스너·느린 게시자 격리·타이머 3케이스·COALESCE 보존

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

---

## Self-Review 결과

- 스펙 §1(게시물 단위 정산) → Task 2, §2(ready 조건) → Task 3, §3(COALESCE) → Task 1, 테스트 목록 1~8 → Task 1(6), Task 2(1·5·8), Task 3(2·3·4·7 — 7은 기존 테스트 유지로 커버). 갭 없음.
- 타입 일관성: `enrich(BrandRow, List<PostInfo>, Consumer<String>)` — Task 2 정의·Task 3 소비 일치. 생성자 인자 순서 명시. `settledCodes`/`enrichedCodes()` 이름 일치.
- 플레이스홀더 없음 — 단, Task 1 Step 1과 Task 4 Step 1·2는 "기존 파일의 관용구를 열어 따르라"는 지시가 의도된 것(파일 형식이 정본).
