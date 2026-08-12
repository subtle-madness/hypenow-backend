# 브랜드 보강(enrichment) 제한 병렬화 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

> 상태: ✅ 실행됨 (2026-08-07 — Task 1~3 완료·archive 이동. 스펙 [2026-08-07-brand-enrich-parallel-design.md](../../specs/archive/2026-08-07-brand-enrich-parallel-design.md))

**Goal:** 브랜드 보강(게시자 프로필+댓글) 순차 Hiker 콜을 공유 워커 풀(동시 6)로 병렬화해 보강 ~3분 → ~30초로 줄인다.

**Architecture:** `brandEnrichWorkerPool`(고정 6스레드, 공유 빈) 추가. `BrandCollectService.enrich` 내부 두 루프(`ensureAuthors`·`collectCommentsGated`)만 제출·대기 구조로 변경 — 브랜드 단위 큐잉(기존 단일 스레드 executor 2개)과 게이트·격리·백스톱 의미 불변. 공유 빈이라 스윕·등록이 겹쳐도 전역 동시 Hiker 콜 ≤ 6+core 1 = 7(실측 한계 8 이내).

**Tech Stack:** Java 21, Spring Boot 4.1, `CompletableFuture.runAsync` + `allOf().join()`, 테스트는 기존 fake HikerHttp 관용구(DB 없음).

## Global Constraints

- 주석·로그·커밋 메시지는 한국어, 커밋 prefix `feat(monitoring):` 식 (CLAUDE.md).
- 테스트는 모듈 단위: `./gradlew :monitoring:test` (전체 `./gradlew test`는 PR 직전에만).
- Testcontainers용 `export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock` 필요.
- 게이트 로직·워터마크 갱신·실패 전파 규칙(게시자/게시물 단위 격리, 로그만)은 한 글자도 안 바꾼다 (스펙 §3).
- 설정 키 `monitoring.brand.enrich-concurrency`, 기본 6 (스펙 §2).

---

### Task 1: enrich 내부 병렬화 + 테스트

**Files:**
- Modify: `monitoring/src/main/java/com/celfit/monitoring/service/BrandCollectService.java` (생성자 + `ensureAuthors` + `collectCommentsGated`)
- Test: `monitoring/src/test/java/com/celfit/monitoring/service/BrandCollectServiceTest.java`
- Test: `monitoring/src/test/java/com/celfit/monitoring/service/BrandRegistrationServiceTest.java` (StubCollect super 시그니처만)

**Interfaces:**
- Produces: `BrandCollectService` 생성자 7번째 파라미터 `Executor enrichWorker` (기존 `AuthorProfileRepository authors` 뒤, `@Value int windowDays` 앞). Task 2가 `@Qualifier("brandEnrichWorkerPool")`를 이 파라미터에 붙인다.

- [ ] **Step 1: 생성자에 `Executor enrichWorker` 파라미터 추가(동작 변경 없이 필드 보관만)**

`BrandCollectService.java`: import에 `java.util.concurrent.CompletableFuture`, `java.util.concurrent.Executor` 추가. 필드 `private final Executor enrichWorker;` 추가. 생성자는 `AuthorProfileRepository authors` 다음에 `Executor enrichWorker` 파라미터를 넣고 `this.enrichWorker = enrichWorker;` 대입 (`@Qualifier`는 Task 2에서 — 이 시점엔 컴파일만 맞춘다).

- [ ] **Step 2: 두 테스트의 생성자 호출부 보정**

`BrandCollectServiceTest.java`의 `service(int windowPosts)` 헬퍼 — 직결 executor로 기존 테스트의 결정성(호출 순서·계수) 유지:

```java
private BrandCollectService service(int windowPosts) {
	return new BrandCollectService(client(), writer, snapshots, comments, tagged, authors,
			Runnable::run, 90, windowPosts, 3, 30);
}
```

`BrandRegistrationServiceTest.java`의 `StubCollect` 생성자:

```java
StubCollect() {
	super(null, null, null, null, null, null, null, 90, 105, 3, 30);
}
```

- [ ] **Step 3: 컴파일 + 기존 테스트 green 확인**

Run: `export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock && ./gradlew :monitoring:test --tests "com.celfit.monitoring.service.BrandCollectServiceTest" --tests "com.celfit.monitoring.service.BrandRegistrationServiceTest"`
Expected: PASS (동작 변화 없음 — 파라미터만 늘었다)

- [ ] **Step 4: 실패하는 동시성 테스트 작성**

`BrandCollectServiceTest.java`에 추가. 먼저 `InMemoryAuthors.upserted`를 스레드 안전으로 바꾼다(병렬 upsert 대비 — 기존 테스트는 직결 executor라 영향 없음):

```java
final List<String> upserted = Collections.synchronizedList(new ArrayList<>());
```

(import `java.util.Collections` 추가.) 그리고 테스트 본문 — CyclicBarrier(3)은 "3콜이 동시에 나가야만" 통과한다. 순차 실행이면 barrier가 영원히 못 모여 태스크가 타임아웃 예외로 죽고(태스크 내 catch가 삼켜 로그만 남음) upserted가 비어 두 번째 단언이 잡는다:

```java
// ── 보강 병렬화(동시 6 — 2026-08-07 스펙) ────────────────────────────────

@Test
void 보강_게시자_콜은_워커_풀_동시성으로_나가되_상한을_넘지_않는다() {
	tagPages.add(page(null,
			reel("A", RECENT, 0, 201, ""), reel("B", RECENT, 0, 202, ""),
			reel("C", RECENT, 0, 203, ""), reel("D", RECENT, 0, 204, ""),
			reel("E", RECENT, 0, 205, ""), reel("F", RECENT, 0, 206, "")));
	AtomicInteger inFlight = new AtomicInteger();
	AtomicInteger maxInFlight = new AtomicInteger();
	CyclicBarrier trio = new CyclicBarrier(3);   // 3콜이 "동시에" 모여야 통과 — 순차면 못 모인다
	HikerClient latched = new HikerClient(path -> {
		if (path.startsWith("/v2/user/by/username")) {
			return BRAND_PROFILE_JSON;
		}
		if (path.startsWith("/v2/user/tag/medias")) {
			return tagPages.get(0);
		}
		if (path.startsWith("/v2/user/by/id")) {
			maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
			try {
				trio.await(5, TimeUnit.SECONDS);
			} catch (Exception e) {
				throw new IllegalStateException("동시 3 미달 — 병렬 실행 안 됨", e);
			} finally {
				inFlight.decrementAndGet();
			}
			String id = path.substring(path.indexOf("?id=") + "?id=".length());
			return "{\"user\":{\"pk\":%s,\"username\":\"author_%s\"}}".formatted(id, id);
		}
		throw new IllegalStateException("예상 밖 콜: " + path);
	});
	ExecutorService pool = Executors.newFixedThreadPool(3);
	try {
		BrandCollectService svc = new BrandCollectService(latched, writer, snapshots,
				comments, tagged, authors, pool, 90, 105, 3, 30);
		svc.enrich(brand, svc.sweepCore(brand));
	} finally {
		pool.shutdown();
	}
	assertThat(maxInFlight.get()).isEqualTo(3);   // 풀 크기까지 도달, 초과 없음
	assertThat(authors.upserted)
			.containsExactlyInAnyOrder("201", "202", "203", "204", "205", "206");
}
```

(import 추가: `java.util.concurrent.CyclicBarrier`, `java.util.concurrent.ExecutorService`, `java.util.concurrent.Executors`, `java.util.concurrent.TimeUnit`, `java.util.concurrent.atomic.AtomicInteger`.)
게시물 6건은 comment_count 0이라 댓글 게이트가 자동으로 닫혀(`0 <= 저장값 0`) 댓글 콜은 없다 — 테스트는 게시자 경로만 본다.

- [ ] **Step 5: 실패 확인**

Run: `./gradlew :monitoring:test --tests "com.celfit.monitoring.service.BrandCollectServiceTest"`
Expected: 새 테스트 FAIL — 순차 실행이라 barrier 타임아웃(약 5초×6콜 지연 후) → `upserted` 비어 `containsExactlyInAnyOrder` 실패

- [ ] **Step 6: `ensureAuthors`·`collectCommentsGated` 병렬화 구현**

`BrandCollectService.java` — 두 메서드의 for 루프 본문을 워커 풀 제출로 바꾼다. 게이트 판정·선행 쿼리(1회)·try/catch 격리 의미는 그대로(태스크 안으로 이동만):

```java
private void ensureAuthors(Collection<PostInfo> posts) {
	Set<String> ids = posts.stream().map(PostInfo::ownerUserId).filter(Objects::nonNull)
			.collect(Collectors.toCollection(LinkedHashSet::new));
	if (ids.isEmpty()) {
		return;
	}
	Set<String> fresh = authors.freshIgUserIds(ids,
			Instant.now().minus(Duration.ofDays(authorStaleDays)));
	// 게시자별 독립 콜이라 워커 풀(동시 6)로 병렬화한다(2026-08-07 스펙 — 콜당 ~1.5초 순차가
	// 보강 시간의 본체였다). 격리 규칙은 그대로: 한 명의 실패는 로그만, 나머지는 계속.
	List<CompletableFuture<Void>> tasks = new ArrayList<>();
	for (String id : ids) {
		if (fresh.contains(id)) {
			continue;
		}
		tasks.add(CompletableFuture.runAsync(() -> {
			try {
				authors.upsert(hiker.fetchAuthorProfile(id));
			} catch (RuntimeException e) {
				log.warn("게시자 프로필 수집 실패(격리) — user_id {}: {}", id, e.toString());
			}
		}, enrichWorker));
	}
	CompletableFuture.allOf(tasks.toArray(CompletableFuture[]::new)).join();
}
```

```java
private void collectCommentsGated(long brandId, Collection<PostInfo> posts) {
	List<PostInfo> candidates = posts.stream().filter(p -> p.comments() != null).toList();
	if (candidates.isEmpty()) {
		return;
	}
	Map<String, Long> stored = taggedPosts.commentsCollectedCounts(brandId,
			candidates.stream().map(PostInfo::shortCode).toList());
	// 게시물별 독립 콜이라 워커 풀(동시 6)로 병렬화한다(ensureAuthors와 같은 근거). 게이트
	// 판정은 제출 전에, 워터마크 갱신은 태스크 안에서 — 의미 불변, 실행만 동시.
	List<CompletableFuture<Void>> tasks = new ArrayList<>();
	for (PostInfo p : candidates) {
		if (p.comments() <= stored.getOrDefault(p.shortCode(), 0L)) {
			continue;
		}
		tasks.add(CompletableFuture.runAsync(() -> {
			try {
				List<CommentInfo> fetched = hiker.fetchComments(p.shortCode(), p.username(),
						commentPages, comments.findIds(p.shortCode()));
				comments.upsertForPost(p.shortCode(), fetched);
				// 저장값은 열거 관측치로 갱신한다 — 다음 게이트가 "그 사이 증가분"만 보게.
				taggedPosts.updateCommentsCollected(brandId, p.shortCode(), p.comments());
			} catch (RuntimeException e) {
				log.warn("태그 댓글 수집 실패(격리) — 게시물 {}: {}", p.shortCode(), e.toString());
			}
		}, enrichWorker));
	}
	CompletableFuture.allOf(tasks.toArray(CompletableFuture[]::new)).join();
}
```

(import `java.util.ArrayList` 추가.) `enrich()`는 호출 스레드(스케줄러 또는 brand-enrich)에서 제출·대기만 하므로 중첩 제출 없음 → 고정 풀 데드락 없음.

- [ ] **Step 7: 전 테스트 green 확인**

Run: `./gradlew :monitoring:test --tests "com.celfit.monitoring.service.BrandCollectServiceTest" --tests "com.celfit.monitoring.service.BrandRegistrationServiceTest"`
Expected: 전부 PASS (기존 테스트는 직결 executor라 순서 단언 유지, 새 테스트는 barrier 통과)

- [ ] **Step 8: Commit**

```bash
git add monitoring/src/main/java/com/celfit/monitoring/service/BrandCollectService.java monitoring/src/test/java/com/celfit/monitoring/service/BrandCollectServiceTest.java monitoring/src/test/java/com/celfit/monitoring/service/BrandRegistrationServiceTest.java
git commit -m "feat(monitoring): 브랜드 보강 게시자·댓글 콜 병렬화 — 워커 풀 주입, 게이트·격리 의미 불변

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 2: 워커 풀 빈 배선 + 설정 키 + 낡은 주석 갱신

**Files:**
- Modify: `monitoring/src/main/java/com/celfit/monitoring/config/BrandBackfillConfig.java`
- Modify: `monitoring/src/main/java/com/celfit/monitoring/service/BrandCollectService.java` (생성자 `@Qualifier`)
- Modify: `monitoring/src/main/java/com/celfit/monitoring/service/BrandRegistrationService.java` (javadoc만)
- Modify: `monitoring/src/main/resources/application.yml`

**Interfaces:**
- Consumes: Task 1의 `BrandCollectService` 생성자 7번째 `Executor enrichWorker` 파라미터.
- Produces: 빈 `brandEnrichWorkerPool` (`Executor`, 고정 `${monitoring.brand.enrich-concurrency:6}`스레드).

- [ ] **Step 1: `BrandBackfillConfig`에 워커 풀 빈 추가 + 클래스 javadoc 갱신**

```java
@Bean(name = "brandEnrichWorkerPool")
public Executor brandEnrichWorkerPool(
		@Value("${monitoring.brand.enrich-concurrency:6}") int concurrency) {
	AtomicInteger seq = new AtomicInteger();
	return Executors.newFixedThreadPool(concurrency, r -> {
		Thread t = new Thread(r, "brand-enrich-worker-" + seq.incrementAndGet());
		t.setDaemon(true);
		return t;
	});
}
```

(import `java.util.concurrent.atomic.AtomicInteger`, `org.springframework.beans.factory.annotation.Value` 추가.) 클래스 javadoc의 "직렬화가 Hiker 부하 완충이 되고(동시 콜 최대 2)" 문장을 실측 반영으로 교체 — 예:

```
직렬화는 브랜드 단위 큐잉·순서 보장용이고, Hiker 콜 병렬화는 enrich 내부의
brandEnrichWorkerPool(동시 6 — 08-07 운영 실측: 동시 8까지 레이턴시 열화·429 전무)이 담당한다.
전역 동시 콜은 워커 6 + core 1 = 최대 7로 실측 한계(8) 안이다.
```

- [ ] **Step 2: `BrandCollectService` 생성자 파라미터에 `@Qualifier` 부착**

```java
@Qualifier("brandEnrichWorkerPool") Executor enrichWorker,
```

(import `org.springframework.beans.factory.annotation.Qualifier` 추가.) Executor 빈이 여럿(backfill·enrich·metricsBackfill)이라 없으면 기동 실패한다.

- [ ] **Step 3: `BrandRegistrationService` javadoc의 "두 executor 모두 단일 스레드 — 동시 Hiker 콜은 최대 2개" 문장 교체**

```
두 executor 모두 단일 스레드(브랜드 단위 큐잉·순서 보장). Hiker 콜 병렬화는 enrich 내부
워커 풀이 담당 — 전역 동시 콜 최대 7(= 워커 6 + core 1, BrandBackfillConfig 참조).
```

- [ ] **Step 4: `application.yml`의 `monitoring.brand`에 키 추가**

`author-stale-days: 30` 줄 다음에:

```yaml
    enrich-concurrency: 6       # 보강(게시자·댓글) 워커 풀 크기 — 08-07 운영 실측(동시 8 무저항)에서 마진 둔 값
```

- [ ] **Step 5: 모듈 전체 테스트(스프링 배선 포함) green 확인**

Run: `export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock && ./gradlew :monitoring:test`
Expected: 전부 PASS — 컨텍스트 로드 테스트가 빈 배선(@Qualifier 누락 등)을 잡는다

- [ ] **Step 6: Commit**

```bash
git add monitoring/src/main/java/com/celfit/monitoring/config/BrandBackfillConfig.java monitoring/src/main/java/com/celfit/monitoring/service/BrandCollectService.java monitoring/src/main/java/com/celfit/monitoring/service/BrandRegistrationService.java monitoring/src/main/resources/application.yml
git commit -m "feat(monitoring): brandEnrichWorkerPool(동시 6) 배선 — 보강 ~3분→~30초, 전역 동시 콜 상한 7

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 3: 문서 갱신 + PR

**Files:**
- Modify: `DECISIONS.md` (맨 위에 결정 1행)
- Modify: `docs/tracks/MON-BT-브랜드-태그-모니터링.md` (트랙 상태 갱신)
- Modify: `docs/superpowers/specs/2026-08-07-brand-enrich-parallel-design.md` (상태 헤더 🟢→✅)
- Move: `docs/superpowers/plans/2026-08-07-brand-enrich-parallel.md` → `docs/superpowers/plans/archive/`

**Interfaces:**
- Consumes: Task 1·2 완료(구현·테스트 green).

- [ ] **Step 1: DECISIONS.md 맨 위에 결정 추가**

기존 최상단 행 형식을 그대로 따라 1개 항목 추가. 내용 요지: "브랜드 보강 Hiker 콜 병렬화(동시 6) — 08-07 운영 실측(동시 8까지 429·레이턴시 열화 전무)으로 '동시 2 = 부하 완충' 전제 반증. 공유 워커 풀로 전역 동시 콜 ≤ 7 보장. 보강 ~3분 → ~30초. 스펙 docs/superpowers/specs/2026-08-07-brand-enrich-parallel-design.md."

- [ ] **Step 2: 트랙 파일 갱신**

`docs/tracks/MON-BT-브랜드-태그-모니터링.md`의 최근 상태 섹션에 이번 변경(보강 병렬화, ready~30초 + 보강 완료 ~1분) 한 줄 추가 — 파일의 기존 서술 형식을 따른다.

- [ ] **Step 3: 스펙 상태 헤더를 ✅ 구현/반영됨으로 갱신, 계획 문서를 archive로 이동**

```bash
mkdir -p docs/superpowers/plans/archive
git mv docs/superpowers/plans/2026-08-07-brand-enrich-parallel.md docs/superpowers/plans/archive/
```

- [ ] **Step 4: Commit + push + PR**

```bash
git add DECISIONS.md docs/
git commit -m "docs: 브랜드 보강 병렬화 결정 기록·트랙 갱신, 스펙 ✅ 전환

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
git push -u origin feature/issue-363-ready-response-fabde9
```

PR은 develop 대상, 본문에 실측 표(순차 11s vs 동시 4/8 각 2s, 429 전무)와 효과(등록→보강 완료 ~3.5분→~1분), 전역 상한 7 근거를 요약. `gh pr create` 사용.
