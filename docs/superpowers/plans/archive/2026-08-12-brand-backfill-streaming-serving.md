# 브랜드 백필 페이지 스트리밍 적재 + 조기 서빙 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

> 상태: ✅ 실행 완료(2026-08-12) · 스펙: [2026-08-12-brand-backfill-streaming-serving-design.md](../../specs/2026-08-12-brand-backfill-streaming-serving-design.md)

**Goal:** 브랜드 등록 백필이 365일 열거를 끝내기 전에, 최근 30일 커버 시점에 FE ready(`last_swept_at`)를 당기고 페이지마다 즉시 적재한다 — tooq.official 실측 8분 24초 → 약 1분 30초.

**Architecture:** monitoring 모듈만 변경. `BrandCollectService.sweepCore`를 페이지 단위 처리로 재구성하고 서빙 콜백을 추가, `BrandRepository.markServing`(last_swept_at만 갱신) 신설, `BrandRegistrationService`가 콜백에서 markServing + 선행 보강을 수행. 스키마·was·FE 계약 변경 없음.

**Tech Stack:** Java 21, Spring Boot 4.1, JdbcTemplate, JUnit 5 + AssertJ(가짜 HikerHttp 관용구), Testcontainers(스토어 테스트).

## Global Constraints

- 서빙 창 설정 키: `monitoring.brand.serving-window-days`, 기본값 `30`.
- `last_swept_on`·`backfill_completed_at`은 **완주 시점(touchSwept)에만** 갱신 — markServing은 `last_swept_at`만, 그것도 `IS NULL`일 때만.
- FE/was 계약·DB 스키마 변경 금지(마이그레이션 없음).
- 열거 종료 판정 4종(자연 종료·커서 소진·안전 상한·커서 미전진)과 `coveredCutoff`/`touchCrawledDepth` 의미 불변.
- 주석·커밋 메시지는 한국어, 커밋 prefix `feat(monitoring):`/`test(monitoring):`/`docs:`.
- 테스트는 모듈 단위: `./gradlew :monitoring:test`. 이 머신 로컬 도커는 Docker Desktop(`DOCKER_HOST` 미설정이 정답 — 08-09 확인. CLAUDE.md의 colima 안내는 colima 머신용).

---

### Task 1: BrandRepository.markServing 신설

**Files:**
- Modify: `monitoring/src/main/java/com/celfit/monitoring/store/BrandRepository.java` (touchSwept 아래에 메서드 추가)
- Test: `monitoring/src/test/java/com/celfit/monitoring/store/BrandStoreTest.java`

**Interfaces:**
- Produces: `public void markServing(long brandId)` — Task 3의 등록 서비스가 서빙 콜백에서 호출.

- [ ] **Step 1: 실패하는 테스트 작성**

`BrandStoreTest`의 `스윕_완주일과_프로필_갱신` 테스트 아래에 추가(기존 `profile(...)`·`column(...)` 헬퍼 재사용):

```java
@Test
void markServing은_last_swept_at만_당기고_완주_컬럼은_건드리지_않는다() {
	long id = brands.insertOrReactivate("brandx", profile("brandx", "111", 1000L, "소개"));

	brands.markServing(id);

	assertThat(column(id, "last_swept_at", Timestamp.class)).isNotNull();   // was ready 신호만
	assertThat(brands.findByUsername("brandx").orElseThrow().lastSweptOn()).isNull();
	assertThat(column(id, "backfill_completed_at", Timestamp.class)).isNull();
}

@Test
void markServing은_이미_서빙_중이면_시각을_덮지_않는다() {
	long id = brands.insertOrReactivate("brandx", profile("brandx", "111", 1000L, "소개"));
	brands.touchSwept(id, LocalDate.of(2026, 8, 6));   // 완주 — last_swept_at 확정
	Timestamp sweptAt = column(id, "last_swept_at", Timestamp.class);

	brands.markServing(id);   // 가드(IS NULL) — no-op이어야 한다

	assertThat(column(id, "last_swept_at", Timestamp.class)).isEqualTo(sweptAt);
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :monitoring:test --tests "com.celfit.monitoring.store.BrandStoreTest"`
Expected: 컴파일 실패 — `markServing` 미정의.

- [ ] **Step 3: 구현**

`BrandRepository.java`의 `touchSwept` 메서드 바로 아래에 추가:

```java
/**
 * 조기 서빙 마크(스트리밍 백필 2026-08-12 스펙 §1) — 등록 백필이 서빙 창(최근 30일)을 커버한
 * 시점에 was ready 판정 컬럼(last_swept_at)만 당긴다. last_swept_on(다음 스윕 열거 깊이 판정)과
 * backfill_completed_at(FE "과거분 수집 중" 배지)은 완주 시점의 touchSwept가 찍는다 — 여기서
 * last_swept_on까지 찍으면 이후 열거 실패 시 다음 스윕이 14일 컷만 돌아 30~365일 구간이 영구
 * 공백이 된다. IS NULL 가드: 첫 백필에서만 유효(재가입·이미 서빙 중이면 no-op).
 */
public void markServing(long brandId) {
	db.update("UPDATE brand_account SET last_swept_at = now() WHERE id = ? AND last_swept_at IS NULL",
			brandId);
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :monitoring:test --tests "com.celfit.monitoring.store.BrandStoreTest"`
Expected: PASS (신규 2개 포함 전부).

- [ ] **Step 5: 커밋**

```bash
git add monitoring/src/main/java/com/celfit/monitoring/store/BrandRepository.java monitoring/src/test/java/com/celfit/monitoring/store/BrandStoreTest.java
git commit -m "feat(monitoring): 브랜드 조기 서빙 마크(markServing) — last_swept_at만 당긴다"
```

---

### Task 2: BrandCollectService 페이지 스트리밍화 + 서빙 콜백

**Files:**
- Modify: `monitoring/src/main/java/com/celfit/monitoring/service/BrandCollectService.java` (sweepCore 재구성, processCore → processPage 대체, 생성자 파라미터 추가)
- Modify: `monitoring/src/main/resources/application.yml` (`monitoring.brand` 블록에 키 추가)
- Test: `monitoring/src/test/java/com/celfit/monitoring/service/BrandCollectServiceTest.java`

**Interfaces:**
- Consumes: 없음(저장 계층 시그니처 불변).
- Produces: `public List<PostInfo> sweepCore(BrandRow brand, Consumer<List<PostInfo>> onServingCovered)` — 서빙 창 커버 시(또는 그전에 열거가 끝나면 종료 시) **정확히 1회**, 그때까지 적재된 편입분 누적 리스트로 호출. 기존 `sweepCore(BrandRow)`는 no-op 콜백 위임으로 유지. 생성자는 `registrationWindowDays` 뒤에 `int servingWindowDays` 추가 — 전체 순서 `(hiker, writer, snapshots, comments, taggedPosts, authors, enrichWorker, registrationWindowDays, servingWindowDays, maxPostsPerSweep, commentPages, authorStaleDays)`. Task 3이 두-인자 sweepCore를 소비한다.

- [ ] **Step 1: 기존 테스트 헬퍼의 생성자 호출 갱신(컴파일 유지)**

`BrandCollectServiceTest.service(int)`:

```java
private BrandCollectService service(int maxPostsPerSweep) {
	return new BrandCollectService(client(), writer, snapshots, comments, tagged, authors,
			Runnable::run, 365, 30, maxPostsPerSweep, 3, 30);
}
```

같은 파일 `보강_게시자_콜은_워커_풀_동시성으로_나가되_상한을_넘지_않는다`의 직접 생성 부분:

```java
BrandCollectService svc = new BrandCollectService(latched, writer, snapshots,
		comments, tagged, authors, pool, 365, 30, 2000, 3, 30);
```

`BrandRegistrationServiceTest.StubCollect` 생성자(Task 3에서 다시 손대지만 컴파일 유지용으로 지금 같이):

```java
StubCollect() {
	super(null, null, null, null, null, null, null, 365, 30, 2000, 3, 30);
}
```

- [ ] **Step 2: 실패하는 테스트 작성**

`BrandCollectServiceTest`에 필드·상수 추가:

```java
private boolean tagPage2Fails = false;   // 필드 블록(tagNotFound 옆)에 추가
```

fake HikerHttp의 `/v2/user/tag/medias` 분기를 다음으로 교체(기존 `tagNotFound` 처리 유지):

```java
if (path.startsWith("/v2/user/tag/medias")) {
	if (tagNotFound) {
		throw new SubjectNotFoundException("Entries not found");
	}
	if (tagPage2Fails && tagCall >= 1) {
		throw new HikerFetchException("열거 2페이지 500");
	}
	return tagPages.get(Math.min(tagCall++, tagPages.size() - 1));
}
```

새 테스트 섹션 추가(`// ── core/enrichment 분리` 섹션 위에):

```java
// ── 스트리밍 적재 + 서빙 콜백(2026-08-12 스펙 §2) ────────────────────────

@Test
void 서빙_창_커버_시점에_콜백을_1회_호출하고_열거는_계속한다() {
	// 백필 경로(365일 컷). 2페이지 전체가 60일령 > 서빙 창(30일) — 여기서 콜백이 떠야 한다.
	tagPages.add(page("p2", reel("A", RECENT, 0, 101, "")));
	tagPages.add(page("p3", reel("Old60a", RETRO_IN_WINDOW, 0, 102, ""),
			reel("Old60b", RETRO_IN_WINDOW, 0, 103, "")));
	tagPages.add(page(null, reel("Old95", OLD_95D, 0, 104, "")));
	List<List<String>> callbacks = new ArrayList<>();

	service(2000).sweepCore(brand,
			early -> callbacks.add(early.stream().map(PostInfo::shortCode).toList()));

	assertThat(callbacks).hasSize(1);
	assertThat(callbacks.getFirst()).containsExactly("A", "Old60a", "Old60b");   // 경계 페이지까지 누적분
	assertThat(tagCalls()).isEqualTo(3);   // 콜백 후에도 365일 컷까지 계속 — 조기 종료 아님
	assertThat(tagged.inserted).containsExactlyInAnyOrder("A", "Old60a", "Old60b", "Old95");
}

@Test
void 서빙_창보다_게시물이_얕으면_열거_종료_시점에_콜백한다() {
	tagPages.add(page(null, reel("A", RECENT, 0, 101, "")));   // 전부 최근 — 경계 미도달
	List<List<String>> callbacks = new ArrayList<>();

	service(2000).sweepCore(brand,
			early -> callbacks.add(early.stream().map(PostInfo::shortCode).toList()));

	assertThat(callbacks).hasSize(1);
	assertThat(callbacks.getFirst()).containsExactly("A");
}

@Test
void 안전_상한_중단도_종료_시점에_콜백한다() {
	tagPages.add(page("p2", reel("A", RECENT, 0, 101, ""), reel("B", RECENT, 0, 102, "")));
	tagPages.add(page("p3", reel("C", RECENT, 0, 103, ""), reel("D", RECENT, 0, 104, "")));
	tagPages.add(page(null, reel("E", RECENT, 0, 105, "")));
	List<Integer> sizes = new ArrayList<>();

	service(3).sweepCore(brand, early -> sizes.add(early.size()));

	assertThat(sizes).containsExactly(4);   // 상한 3 → 2페이지째 중단, 그때까지 적재분 4건
}

@Test
void 열거_중간_실패에도_앞_페이지_적재는_보존된다() {
	// 스트리밍의 핵심 — 구 일괄 processCore였다면 전량 유실됐을 배치다.
	tagPage2Fails = true;
	tagPages.add(page("p2", reel("A", RECENT, 0, 101, "")));

	org.assertj.core.api.Assertions.assertThatThrownBy(() -> service(2000).sweepCore(brand))
			.isInstanceOf(HikerFetchException.class);

	assertThat(writer.saved).extracting(PostInfo::shortCode).containsExactly("A");
	assertThat(tagged.inserted).containsExactly("A");
}

@Test
void 페이지_간_중복_코드는_한_번만_처리한다() {
	// 커서 드리프트로 같은 게시물이 두 페이지에 실려도 적재·링크는 1회(구 putIfAbsent 의미 보존).
	tagPages.add(page("p2", reel("A", RECENT, 0, 101, "")));
	tagPages.add(page(null, reel("A", RECENT, 0, 101, ""), reel("B", RECENT, 0, 102, "")));

	service(2000).sweep(brand);

	assertThat(writer.saved).extracting(PostInfo::shortCode).containsExactly("A", "B");
	assertThat(tagged.inserted).containsExactly("A", "B");
}
```

import 추가: `assertThatThrownBy`를 쓰면 정적 import(`import static org.assertj.core.api.Assertions.assertThatThrownBy;`)로 바꾸고 본문에서 축약 사용.

- [ ] **Step 3: 실패 확인**

Run: `./gradlew :monitoring:test --tests "com.celfit.monitoring.service.BrandCollectServiceTest"`
Expected: 컴파일 실패 — 두-인자 `sweepCore` 미정의. (생성자 갱신분은 Step 1에서 이미 반영돼 그 외 컴파일 오류가 없어야 한다.)

- [ ] **Step 4: 구현 — sweepCore 재구성**

`BrandCollectService.java` 변경:

① import 추가: `java.util.HashSet` 불필요, `java.util.function.Consumer` 추가.

② 필드·생성자 — `registrationWindowDays` 아래에 `servingWindowDays` 추가:

```java
private final int registrationWindowDays;
private final int servingWindowDays;
```

```java
public BrandCollectService(HikerClient hiker, BrandSnapshotWriter writer,
		BrandSnapshotRepository snapshots, BrandCommentRepository comments,
		TaggedPostRepository taggedPosts, AuthorProfileRepository authors,
		@Qualifier("brandEnrichWorkerPool") Executor enrichWorker,
		@Value("${monitoring.brand.registration-window-days:365}") int registrationWindowDays,
		@Value("${monitoring.brand.serving-window-days:30}") int servingWindowDays,
		@Value("${monitoring.brand.max-posts-per-sweep:2000}") int maxPostsPerSweep,
		@Value("${monitoring.brand.comment-pages:3}") int commentPages,
		@Value("${monitoring.brand.author-stale-days:30}") int authorStaleDays) {
	this.hiker = hiker;
	this.writer = writer;
	this.snapshots = snapshots;
	this.comments = comments;
	this.taggedPosts = taggedPosts;
	this.authors = authors;
	this.enrichWorker = enrichWorker;
	this.registrationWindowDays = registrationWindowDays;
	this.servingWindowDays = servingWindowDays;
	this.maxPostsPerSweep = maxPostsPerSweep;
	this.commentPages = commentPages;
	this.authorStaleDays = authorStaleDays;
}
```

③ 기존 `sweepCore(BrandRow)` 본문과 `processCore` 전체를 아래로 교체(`sweep`·`enrich`·`enumerationCutoff`·`refreshBrandProfileSafely`·`adjustLotteryMetrics`·`ensureAuthors`·`collectCommentsGated`는 그대로):

```java
/** 단일 인자 경로(일일 스윕·기존 호출부) — 서빙 콜백 없이 동작은 동일하다. */
public List<PostInfo> sweepCore(BrandRow brand) {
	return sweepCore(brand, posts -> {});
}

/**
 * core 단계(2026-08-12 스트리밍 개정) — 열거하면서 페이지(~21건)마다 즉시 적재한다. 구 일괄
 * processCore 대비 의미 불변이고 실행 시점만 당겨진다: 중간 실패 시 앞 페이지 적재분이
 * 보존되고(다음 스윕이 잔여를 백스톱), 등록 백필은 서빙 창 커버 시점에 FE ready를 당길 수 있다.
 *
 * <p>onServingCovered는 <b>정확히 1회</b> 호출된다(예외로 중단되는 경우 제외) — 페이지 전체가
 * 서빙 창(servingWindowDays)보다 오래된 순간(소급 태그 혼입 대비, 컷 판정과 같은 보수 규칙),
 * 그전에 열거가 끝나면(자연 종료·상한·미전진 포함) 종료 시점. 인자는 그때까지 적재된 편입분
 * 누적 리스트다. 열거 중단 4종·coveredCutoff·touchCrawledDepth 의미는 기존과 동일하다.
 */
public List<PostInfo> sweepCore(BrandRow brand, Consumer<List<PostInfo>> onServingCovered) {
	refreshBrandProfileSafely(brand);
	Instant now = Instant.now();
	Instant cutoff = enumerationCutoff(brand, now);
	Instant servingCutoff = now.minus(Duration.ofDays(servingWindowDays));
	LocalDate today = LocalDate.now(KST);
	Set<String> known = taggedPosts.knownCodes(brand.id());
	Set<String> seen = new LinkedHashSet<>();      // 이번 실행 처리분 — 페이지 간 중복(커서 드리프트) 스킵
	List<PostInfo> collected = new ArrayList<>();  // 편입분 누적 — 콜백·반환(보강 입력)
	int freshTotal = 0;
	boolean servingMarked = false;
	String cursor = null;
	boolean coveredCutoff = false;
	while (true) {
		HikerClient.TaggedPage page = hiker.fetchTaggedPage(brand.igUserId(), cursor);
		if (page.posts().isEmpty()) {
			// 태그 0건(404 → 빈 페이지)·커서 종료는 자연 종료. 반대로 아직 커서가 살아 있는데
			// 빈 페이지가 오는 건 일시 오류와 구분할 수 없어 커버로 치지 않는다(보수적 판정).
			coveredCutoff = page.nextPageId() == null || seen.isEmpty();
			break;
		}
		List<PostInfo> newItems = page.posts().stream()
				.filter(p -> seen.add(p.shortCode()))   // 첫 관측 유지(구 putIfAbsent 의미)
				.toList();
		int knownBefore = known.size();
		collected.addAll(processPage(brand, newItems, known, today, now));
		freshTotal += known.size() - knownBefore;
		// 서빙 경계 — 페이지 전체가 서빙 창 이전이면 최근 30일은 다 훑었다(taken_at 미상은
		// 컷 판정과 같은 이유로 "이전" 판정에 넣지 않는다). 열거는 계속된다.
		if (!servingMarked && page.posts().stream().allMatch(p -> p.takenAt() != null
				&& Instant.ofEpochSecond(p.takenAt()).isBefore(servingCutoff))) {
			servingMarked = true;
			onServingCovered.accept(List.copyOf(collected));
		}
		boolean wholePageBeforeCutoff = page.posts().stream()
				.allMatch(p -> p.takenAt() != null
						&& Instant.ofEpochSecond(p.takenAt()).isBefore(cutoff));
		if (wholePageBeforeCutoff || page.nextPageId() == null) {
			coveredCutoff = true;
			break;
		}
		// 상한 판정은 커서 소진 판정 뒤에 둔다 — 마지막 페이지에서 정확히 상한에 닿는 건
		// 자연 종료지 폭주가 아니라, 여기서 경고를 찍으면 오보가 된다.
		if (seen.size() >= maxPostsPerSweep) {
			log.warn("태그 열거 안전 상한({}) 도달 — 브랜드 {} 정상 경로에서 닿으면 안 되는 값, 열거 중단",
					maxPostsPerSweep, brand.username());
			break;
		}
		if (newItems.isEmpty()) {
			log.warn("태그 커서 미전진 의심 — 브랜드 {} 신규 code 0건, 열거 중단", brand.username());
			break;
		}
		cursor = page.nextPageId();
	}
	if (!servingMarked) {
		// 서빙 창까지 못 갔거나(게시물이 얕음) 상한·미전진 중단 — 있는 만큼이라도 서빙을 연다.
		onServingCovered.accept(List.copyOf(collected));
	}
	log.info("브랜드 태그 수집 — {} 열거 {}건, 편입 컷 안 {}건, 신규 {}건",
			brand.username(), seen.size(), collected.size(), freshTotal);
	if (coveredCutoff) {
		// 열거에 더 안 실리는 링크(삭제·태그 제거·비공개 전환)까지 포함해 커버한 깊이 전체를
		// touch — 안 하면 그 링크의 due가 영구 true로 굳어 매 스윕이 같은 깊이를 다시 연다.
		taggedPosts.touchCrawledDepth(brand.id(), cutoff, now);
	}
	return List.copyOf(collected);
}

/**
 * 페이지 1개분 처리(구 processCore의 페이지 단위판) — 편입 컷(365일) 필터 → 복권 지표 보정 →
 * 스냅샷 적재 → 신규 링크(known 갱신) → last_crawled_at 갱신. 전부 upsert/멱등이라 재실행 안전.
 */
private List<PostInfo> processPage(BrandRow brand, List<PostInfo> posts, Set<String> known,
		LocalDate today, Instant now) {
	Instant enrollCutoff = now.minus(Duration.ofDays(registrationWindowDays));
	// taken_at 미상은 보수적으로 제외(잘못된 편입 방지) — 다음 열거에서 채워지면 잡힌다.
	List<PostInfo> inWindow = posts.stream()
			.filter(p -> p.takenAt() != null
					&& !Instant.ofEpochSecond(p.takenAt()).isBefore(enrollCutoff))
			.toList();
	if (inWindow.isEmpty()) {
		return List.of();
	}
	List<PostInfo> adjusted = adjustLotteryMetrics(inWindow);
	for (PostInfo p : adjusted) {
		writer.savePost(today, p);
	}
	for (PostInfo p : adjusted) {
		if (known.add(p.shortCode())) {
			taggedPosts.insert(brand.id(), p);
		}
	}
	// 만난 게시물 전부(신규 포함) — 다음 스윕의 티어 판정(due) 입력. 180일 초과분 갱신도
	// 무해하다(판정식이 영구 제외라 이들을 위한 콜은 발생하지 않는다 — 스펙 §4).
	taggedPosts.touchCrawled(brand.id(),
			adjusted.stream().map(PostInfo::shortCode).toList(), now);
	return adjusted;
}
```

④ 클래스 javadoc의 "저장은 …" 문단 뒤에 스트리밍 개정 한 줄 추가:

```
 * <p>2026-08-12 스트리밍 개정: 적재는 페이지 단위로 즉시 일어나고, 등록 백필은 서빙 창(기본
 * 30일) 커버 시점에 콜백으로 FE ready를 당긴다(스펙 docs/superpowers/specs/2026-08-12-…-design.md).
```

⑤ `application.yml`의 `registration-window-days` 줄 아래에 추가:

```yaml
    serving-window-days: 30         # 등록 백필 조기 서빙 창(2026-08-12 스트리밍 스펙 §2) — 이 깊이 커버 시 last_swept_at(FE ready)만 당긴다
```

- [ ] **Step 5: 테스트 통과 확인 (기존 + 신규 전부)**

Run: `./gradlew :monitoring:test --tests "com.celfit.monitoring.service.BrandCollectServiceTest"`
Expected: PASS — 기존 열거·티어·복권·보강 테스트 전부 그대로 통과(동작 의미 불변의 증거) + 신규 5개 통과.

- [ ] **Step 6: 커밋**

```bash
git add monitoring/src/main/java/com/celfit/monitoring/service/BrandCollectService.java monitoring/src/main/resources/application.yml monitoring/src/test/java/com/celfit/monitoring/service/BrandCollectServiceTest.java monitoring/src/test/java/com/celfit/monitoring/service/BrandRegistrationServiceTest.java
git commit -m "feat(monitoring): 브랜드 태그 열거를 페이지 스트리밍 적재로 재구성 — 서빙 창 커버 콜백 추가"
```

---

### Task 3: BrandRegistrationService — 조기 ready + 선행 보강

**Files:**
- Modify: `monitoring/src/main/java/com/celfit/monitoring/service/BrandRegistrationService.java` (runBackfillSafely 교체)
- Test: `monitoring/src/test/java/com/celfit/monitoring/service/BrandRegistrationServiceTest.java`

**Interfaces:**
- Consumes: Task 1 `BrandRepository.markServing(long)`, Task 2 `sweepCore(BrandRow, Consumer<List<PostInfo>>)`.
- Produces: 없음(공개 API 불변).

- [ ] **Step 1: 스텁 갱신 + 실패하는 테스트 작성**

`BrandRegistrationServiceTest` 갱신:

① `InMemoryBrands`에 추가:

```java
final List<Long> served = new ArrayList<>();

@Override
public void markServing(long brandId) {
	served.add(brandId);
}
```

② `StubCollect`의 기존 `sweepCore(BrandRow)` override를 아래로 교체하고 필드 추가(`enriched`는 유지하되 잔여 분리 검증용 `enrichedPosts` 추가):

```java
List<PostInfo> earlyBatch = List.of();   // 서빙 콜백에 넘길 누적분(기본: 없음)
List<PostInfo> fullResult = List.of();   // 완주 반환분
boolean failAfterServing = false;        // 콜백 후 실패 시나리오 주입
final List<List<String>> enrichedPosts = new ArrayList<>();

@Override
public List<PostInfo> sweepCore(BrandRow brand, java.util.function.Consumer<List<PostInfo>> onServingCovered) {
	if (failing.contains(brand.username())) {
		throw new IllegalStateException("백필 실패 주입");
	}
	coreSwept.add(brand.username());
	onServingCovered.accept(earlyBatch);   // 실코드의 "정확히 1회" 계약 재현
	if (failAfterServing) {
		throw new IllegalStateException("서빙 후 실패 주입");
	}
	return fullResult;
}
```

`enrich` override의 기록에 한 줄 추가:

```java
@Override
public void enrich(BrandRow brand, List<PostInfo> posts) {
	if (enrichFailing.contains(brand.username())) {
		throw new IllegalStateException("보강 실패 주입");
	}
	enriched.add(brand.username());
	enrichedPosts.add(posts.stream().map(PostInfo::shortCode).toList());
	callOrder.add("enrich");
}
```

③ 테스트 클래스에 PostInfo 헬퍼 추가(23개 컴포넌트 — shortCode·username·ownerUserId·contentType·rawJson만 실값):

```java
private static PostInfo post(String code) {
	return new PostInfo(code, "author", null, null, "1", "REELS", null, null,
			null, null, null, null, null, null, null, null, null, null, null,
			"{}", false, false, false);
}
```

④ 새 테스트 3개(`core_완료_즉시_ready를_찍고_보강은_전용_큐로_넘긴다` 아래에):

```java
@Test
void 서빙_콜백은_markServing과_선행_보강을_수행하고_잔여만_재보강한다() {
	collect.earlyBatch = List.of(post("A"), post("B"));
	collect.fullResult = List.of(post("A"), post("B"), post("C"));

	var result = service().register("brandx");

	assertThat(brands.served).containsExactly(result.brandId());     // 조기 ready
	assertThat(brands.touched).containsExactly(result.brandId());    // 완주 touchSwept도 그대로
	assertThat(enrichQueue).hasSize(2);                              // 선행 + 잔여
	enrichQueue.forEach(Runnable::run);
	assertThat(collect.enrichedPosts)
			.containsExactly(List.of("A", "B"), List.of("C"));       // 잔여는 선행분 제외
	assertThat(hashtagCollect.swept).containsExactly("brandx");      // 해시태그는 잔여 태스크 꼬리
}

@Test
void 선행분이_비면_선행_보강_태스크를_만들지_않는다() {
	// earlyBatch 기본값 List.of() — 태그가 얕은 브랜드의 헛 태스크 방지.
	var result = service().register("brandx");

	assertThat(brands.served).containsExactly(result.brandId());   // 서빙 마크는 목록이 비어도 정당
	assertThat(enrichQueue).hasSize(1);                            // 잔여(완주) 태스크만
}

@Test
void 서빙_후_core_실패도_touchSwept_없이_backfill_error를_남긴다() {
	collect.failAfterServing = true;
	collect.earlyBatch = List.of(post("A"));

	var result = service().register("brandx");

	assertThat(brands.served).containsExactly(result.brandId());   // 이미 연 서빙은 유지(부분 데이터)
	assertThat(brands.touched).isEmpty();                          // 완주 아님 — 다음 스윕 백스톱
	assertThat(brands.backfillErrors).containsKey(result.brandId());
	assertThat(enrichQueue).hasSize(1);                            // 선행 보강만 제출됨(잔여 태스크 없음)
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :monitoring:test --tests "com.celfit.monitoring.service.BrandRegistrationServiceTest"`
Expected: 신규 3개 FAIL — `served` 비어 있음/`enrichQueue` 크기 불일치(현행 runBackfillSafely는 단일-인자 sweepCore + 단일 enrich 태스크). 기존 테스트 중 `core_완료_즉시_ready...`(hasSize(1))는 아직 통과해야 한다.

- [ ] **Step 3: 구현 — runBackfillSafely 교체**

`BrandRegistrationService.java`: import에 `java.util.HashSet`·`java.util.Set`·`java.util.function.Consumer` 없이도 되도록 아래 그대로(`Set`·`HashSet`만 추가), `runBackfillSafely`를 교체:

```java
/**
 * 백필 core = 매일 스윕과 같은 열거·적재 코드(스트리밍 — 페이지마다 즉시 적재). 서빙 창(30일)
 * 커버 콜백에서 markServing(FE ready만 당김 — last_swept_on은 완주 touchSwept 몫, 스펙 §1)과
 * 선행 보강(그때까지 적재분의 게시자·댓글)을 수행하고, 완주 후엔 잔여분만 보강한다. 선행분
 * 코드 집합으로 잔여를 걸러 이중 보강 콜을 막는다(게시자 fresh 캐시·댓글 워터마크가 있어
 * 겹쳐도 안전하지만 헛 게이트 조회를 줄인다). core 실패는 격리 — 이미 적재된 페이지는 서빙
 * 유지, 잔여 보강도 예약하지 않는다(다음 스윕이 전체를 백스톱).
 */
private void runBackfillSafely(BrandRow row) {
	try {
		Set<String> earlyCodes = new HashSet<>();
		List<PostInfo> posts = collect.sweepCore(row, early -> {
			brands.markServing(row.id());
			early.forEach(p -> earlyCodes.add(p.shortCode()));
			if (!early.isEmpty()) {
				enrich.execute(() -> runEnrichSafely(row, early));
			}
		});
		brands.touchSwept(row.id(), LocalDate.now(KST));
		List<PostInfo> remainder = posts.stream()
				.filter(p -> !earlyCodes.contains(p.shortCode())).toList();
		enrich.execute(() -> {
			runEnrichSafely(row, remainder);
			runHashtagBackfillSafely(row);
		});
	} catch (RuntimeException e) {
		log.warn("브랜드 등록 백필 실패(격리) — {} 다음 스윕이 백스톱: {}", row.username(), e.toString());
		// was 폴링 계약(§5-2) — collecting에서 빠져나올 신호. 다음 스윕 성공(touchSwept)이 클리어한다.
		// markServing 이후 실패면 ready가 이미 열려 있고(부분 데이터 서빙) 이 문구는 FE에서 무시된다.
		brands.markBackfillError(row.id(), "초기 수집에 실패했어요. 자동으로 재시도 중이에요.");
	}
}
```

클래스 javadoc의 core 항목에 스트리밍 문구 반영(기존 "~41콜" 서술 뒤에):

```
 *       2026-08-12 스트리밍 개정: 적재는 페이지 단위 즉시, 서빙 창(30일) 커버 시 markServing으로
 *       ready가 완주보다 먼저 열린다 — tooq.official 실측 8분 24초 → 서빙 창 커버 ~1분 30초.
```

import 추가: `java.util.HashSet`, `java.util.Set`.

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :monitoring:test --tests "com.celfit.monitoring.service.BrandRegistrationServiceTest"`
Expected: PASS 전부. 특히 기존 `core_완료_즉시_ready...`(선행분 없음 → 태스크 1개)·`백필은_enrich_후_해시태그_스윕을_돌린다`(잔여 태스크 안에서 enrich→hashtag 순서)·`core_실패면_보강을_예약하지_않는다`가 무수정 통과해야 한다.

- [ ] **Step 5: 커밋**

```bash
git add monitoring/src/main/java/com/celfit/monitoring/service/BrandRegistrationService.java monitoring/src/test/java/com/celfit/monitoring/service/BrandRegistrationServiceTest.java
git commit -m "feat(monitoring): 등록 백필 서빙 창 커버 시 조기 ready + 선행 보강"
```

---

### Task 4: 모듈 전체 검증 + 문서 갱신

**Files:**
- Modify: `DECISIONS.md` (맨 위에 결정 추가)
- Modify: `docs/superpowers/specs/2026-08-12-brand-backfill-streaming-serving-design.md` (상태 헤더에 구현 계획 링크)

**Interfaces:** 없음.

- [ ] **Step 1: monitoring 모듈 전체 테스트**

Run: `./gradlew :monitoring:test`
Expected: PASS 전부(스토어·수집·등록·스윕·웹 포함). 실패 시 해당 태스크로 돌아가 수정.

- [ ] **Step 2: DECISIONS.md 맨 위에 결정 추가**

```markdown
## 2026-08-12 브랜드 등록 백필 — 페이지 스트리밍 적재 + 서빙 창(30일) 조기 ready

tooq.official 등록 실측 8분 24초(태그 열거 96콜 × p50 4.9초, 커서 체인이라 병렬화 불가)의
본체는 "365일 열거 완주 후 일괄 적재·ready" 구조였다. 열거를 페이지 단위 즉시 적재로 바꾸고,
서빙 창(`monitoring.brand.serving-window-days: 30`) 커버 시점에 `last_swept_at`만 당긴다
(신설 `markServing` — `last_swept_on`·`backfill_completed_at`은 완주 touchSwept 유지: 조기에
last_swept_on을 찍으면 이후 실패 시 다음 스윕이 14일 컷만 돌아 30~365일 영구 공백).
선행 보강(서빙 시점까지 적재분의 게시자·댓글)도 같은 콜백에서 enrich 큐로 제출한다.
FE/was 계약·스키마 변경 없음 — FE는 기존 폴링 + `backfillCompletedAt == null`로 "과거분
수집 중"을 판별한다. 스펙: docs/superpowers/specs/2026-08-12-brand-backfill-streaming-serving-design.md
```

- [ ] **Step 3: 스펙 상태 헤더 갱신**

`2026-08-12-brand-backfill-streaming-serving-design.md` 첫머리를:

```markdown
> 상태: 🟢 활성 · ✅ 구현됨 · 구현 계획: [2026-08-12-brand-backfill-streaming-serving.md](../plans/2026-08-12-brand-backfill-streaming-serving.md)
```

- [ ] **Step 4: 커밋**

```bash
git add DECISIONS.md docs/superpowers/specs/2026-08-12-brand-backfill-streaming-serving-design.md
git commit -m "docs: 브랜드 백필 스트리밍 적재·조기 서빙 결정 기록"
```
