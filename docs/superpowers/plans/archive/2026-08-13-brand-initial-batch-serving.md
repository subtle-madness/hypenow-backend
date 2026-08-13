# 브랜드 초기 수집 완결 배치 서빙 구현 계획

> 상태: ✅ 실행됨(2026-08-13, 커밋 `2d0d9b60`~`43b8a6a7`) · 설계:
> [2026-08-13-brand-initial-batch-serving-design.md](../../specs/2026-08-13-brand-initial-batch-serving-design.md)
>
> **실행 중 계획이 세 번 수정됐다** — 정본은 스펙의 `[정정]` 표시 절(§2·§3·§5)이다.
> Task 6이 지시한 `sweepCore(brand, page -> enrich(...))`와 §5의 "조회 한 곳에 게이트"는
> 그대로 따르면 안 된다(각각 열거 루프 중단·판정 표면 오답).

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 브랜드 게시물 목록에 **보강 정산이 끝난 게시물만** 노출하고, 첫 열거 페이지(21건) 배치가 완결되는 시점에 FE ready를 연다.

**Architecture:** `brand_tagged_post.enriched_at`(정산 시각) 컬럼을 신설해 was 목록 게이트의 정본으로 삼는다. 수집 측은 열거 페이지마다 그 페이지분을 보강한 뒤 정산 마킹하고, **첫 페이지 배치 완료가 곧 `markServing`**(FE ready)이 되어 08-12의 "서빙 창 30일 커버" 기준을 대체한다. 완주 신호는 기존 `collectionCompletedAt`을 재사용한다(status 값 공간 불변).

**Tech Stack:** Java 21 · Spring Boot 4.1 · Gradle 멀티모듈(monitoring/was) · Flyway · JdbcTemplate(monitoring) / JdbcClient(was) · JUnit 5 + AssertJ · Testcontainers PostgreSQL

**설계 정본:** [specs/2026-08-13-brand-initial-batch-serving-design.md](../specs/2026-08-13-brand-initial-batch-serving-design.md)

## Global Constraints

- **주석·로그·커밋 메시지는 한국어.** 커밋 prefix는 `feat(모듈):` / `fix(모듈):` / `docs:`
- **모듈 경계**: monitoring은 자기 DB에 쓰고, was는 monitoring 테이블을 **읽기만** 한다. 두 모듈은 Java 코드를 공유하지 않는다
- **마이그레이션 채번은 UTC 타임스탬프** `V<YYYYMMDDHHMMSS>__<설명>.sql`. monitoring 마지막 채번은 `V20260812220000`이므로 그보다 커야 하고, 가드 v4가 미래 채번(UTC+1h 초과)을 차단하므로 작업 시각 기준 `date -u +%Y%m%d%H%M%S`로 뽑을 것
- **expand-contract**: `ADD COLUMN`(nullable)만 사용. `DROP`·`RENAME`·`SET NOT NULL` 금지
- **배포 순서는 monitoring → was.** 역순이면 was가 없는 컬럼을 조회해 브랜드 목록이 전면 500
- **테스트는 모듈 단위로**: `./gradlew :monitoring:test`, `./gradlew :was:test`. 전체 `./gradlew test`는 PR 직전에만
- **Testcontainers 필수 환경변수**(미설정 시 통합 테스트가 무더기로 죽는다 — 테스트 결함으로 오진하기 쉬움):
  ```bash
  export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock
  ```
  Docker Desktop을 쓰는 머신이면 이 변수를 **설정하지 않는 것**이 정답이다. 먼저 `docker context ls`로 확인할 것
- **`enriched_at`의 의미는 "보강 시도가 끝났다"이지 "필드가 다 찼다"가 아니다.** 게시자 404(실측 2%)·타임아웃(1%)으로 값이 비어도 정산한다. 이 규칙을 깨고 "게시자가 있을 때만 정산"으로 구현하면 그 게시물은 영구 미노출이 된다

## 파일 구조

| 파일 | 책임 | 태스크 |
|---|---|---|
| `monitoring/.../db/migration/V<UTC>__brand_tagged_post_enriched_at.sql` (신규) | 정산 컬럼 + 기존 25,759행 백필 | 1 |
| `monitoring/.../store/TaggedPostRepository.java` | `markEnriched` 추가 | 1 |
| `monitoring/.../service/BrandCollectService.java` | 게시자 404 재시도(`fetchAuthorWithRetry`) · `enrich` 정산 마킹 · `sweepCore` 페이지 콜백화 · `sweep` 통일 · `servingWindowDays` 제거 | 2·3·4·6 |
| `monitoring/.../service/BrandRegistrationService.java` | 첫 배치 ready 배선 | 5 |
| `monitoring/.../config/BrandBackfillConfig.java` + `application.yml` | enrich executor 2 · 워커 10 | 6 |
| `monitoring/.../store/BrandRepository.java` | `expandWindow`에 `backfill_completed_at = NULL` | 7 |
| `was/.../v1/brandmonitoring/BrandAccountAssembler.java` | 도달 불가가 된 확장 분기 제거 | 7 |
| `was/.../monitoring/BrandReadRepository.java` | 목록 게이트 `enriched_at IS NOT NULL` | 8 |
| `was/src/test/resources/monitoring-brand-schema.sql` | 테스트 스키마 미러에 컬럼 추가 | 8 |

---

### Task 1: 정산 컬럼 + 저장 계층

**Files:**
- Create: `monitoring/src/main/resources/db/migration/V<UTC>__brand_tagged_post_enriched_at.sql`
- Modify: `monitoring/src/main/java/com/celfit/monitoring/store/TaggedPostRepository.java`
- Test: `monitoring/src/test/java/com/celfit/monitoring/MigrationTest.java`

**Interfaces:**
- Produces: `TaggedPostRepository.markEnriched(long brandId, Collection<String> codes, Instant at)` — 뒤 태스크가 정산 마킹에 쓴다
- Produces: `brand_tagged_post.enriched_at timestamptz` (nullable) — Task 8의 was 게이트가 읽는다

- [ ] **Step 1: 채번용 UTC 시각을 뽑는다**

```bash
date -u +%Y%m%d%H%M%S
```

이 값을 아래 파일명의 `<UTC>` 자리에 넣는다. `20260812220000`(monitoring 마지막 채번)보다 커야 한다. **KST로 채번하면 미래 번호 선점이 되어 뒤따르는 정상 채번이 전부 Flyway out-of-order 거부에 빠진다**(08-12 운영 크래시루프 2회).

- [ ] **Step 2: 실패하는 테스트를 쓴다**

`MigrationTest.java`에 추가:

```java
/** 정산 컬럼(2026-08-13 스펙 §1) — 기존 행이 백필로 정산 처리됐는지까지 본다. */
@Test
void brand_tagged_post에_enriched_at_컬럼이_있고_기존_행이_백필된다() {
	var ds = TestDb.dataSource(TestDb.container());
	var db = new JdbcTemplate(ds);
	TestDb.resetAndMigrate(db, ds);

	Long column = db.queryForObject("""
			SELECT count(*) FROM information_schema.columns
			WHERE table_schema='public' AND table_name='brand_tagged_post'
			  AND column_name='enriched_at'""", Long.class);
	assertThat(column).isEqualTo(1);

	// 마이그레이션 이후 삽입된 행은 null(아직 미정산)이 정상 — 컬럼이 nullable인지 확인한다.
	Long nullable = db.queryForObject("""
			SELECT count(*) FROM information_schema.columns
			WHERE table_schema='public' AND table_name='brand_tagged_post'
			  AND column_name='enriched_at' AND is_nullable='YES'""", Long.class);
	assertThat(nullable).isEqualTo(1);
}
```

- [ ] **Step 3: 테스트가 실패하는 것을 확인한다**

Run: `./gradlew :monitoring:test --tests "com.celfit.monitoring.MigrationTest"`
Expected: FAIL — `expected: 1 but was: 0`

- [ ] **Step 4: 마이그레이션을 작성한다**

`V<UTC>__brand_tagged_post_enriched_at.sql`:

```sql
-- 게시물별 보강 정산 완료 시각(2026-08-13 완결 배치 서빙 스펙 §1).
-- 의미는 "보강 시도가 끝났다"이지 "게시자·댓글이 다 찼다"가 아니다 — 게시자 404(실측 2%)·
-- 타임아웃(1%)으로 값이 비어도 정산한다. 빈 값은 게시자 stale·댓글 워터마크가 다음 스윕에서 채운다.
ALTER TABLE brand_tagged_post ADD COLUMN enriched_at timestamptz;

-- 기존 행 백필은 필수다 — 빠뜨리면 was 게이트(enriched_at IS NOT NULL) 도입 순간
-- 전 브랜드의 게시물 목록이 통째로 빈다. 이미 보강이 돌았던 행들이라 정산 완료로 보는 것이
-- 사실과 맞고, last_crawled_at(마지막으로 열거에서 만난 시각)이 그 근사치다.
UPDATE brand_tagged_post SET enriched_at = COALESCE(last_crawled_at, first_seen_at);
```

- [ ] **Step 5: 테스트가 통과하는 것을 확인한다**

Run: `./gradlew :monitoring:test --tests "com.celfit.monitoring.MigrationTest"`
Expected: PASS

- [ ] **Step 6: `markEnriched`를 추가한다**

`TaggedPostRepository.java`의 `touchCrawled` 바로 아래에 (같은 IN절 배치 관용구):

```java
/**
 * 보강 정산 마킹(2026-08-13 스펙 §1) — 성공이든 재시도 소진이든 "더 기다릴 이유가 없어진"
 * 게시물에 찍는다. was 목록 게이트의 정본이다. 재마킹은 무해하다(같은 게시물을 다음 스윕이
 * 다시 보강하면 시각만 갱신 — 노출 여부는 안 바뀐다).
 */
public void markEnriched(long brandId, Collection<String> codes, Instant at) {
	if (codes.isEmpty()) {
		return;
	}
	String placeholders = String.join(",", Collections.nCopies(codes.size(), "?"));
	Object[] args = new Object[codes.size() + 2];
	args[0] = Timestamp.from(at);
	args[1] = brandId;
	int i = 2;
	for (String code : codes) {
		args[i++] = code;
	}
	db.update("UPDATE brand_tagged_post SET enriched_at = ? WHERE brand_id = ? AND short_code IN ("
			+ placeholders + ")", args);
}
```

- [ ] **Step 7: 컴파일을 확인한다**

Run: `./gradlew :monitoring:compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 8: 커밋**

```bash
git add monitoring/src/main/resources/db/migration monitoring/src/main/java/com/celfit/monitoring/store/TaggedPostRepository.java monitoring/src/test/java/com/celfit/monitoring/MigrationTest.java
git commit -m "feat(monitoring): 게시물 보강 정산 컬럼 enriched_at 신설 + 기존 행 백필"
```

---

### Task 2: 게시자 프로필 404 재시도 1회

**Files:**
- Modify: `monitoring/src/main/java/com/celfit/monitoring/service/BrandCollectService.java` (`ensureAuthors`)
- Test: `monitoring/src/test/java/com/celfit/monitoring/service/BrandCollectServiceTest.java`

**Interfaces:**
- Consumes: 없음(Task 1과 독립)
- Produces: `ensureAuthors`의 재시도 동작 — Task 3의 정산 규칙이 이 위에 얹힌다

**배경:** `/v2/user/by/id`의 404는 결정적 부재가 아니다. 2026-08-13 실측 99콜 중 2건(2.0%)이 실존 계정에서 404였고 **재시도 1회로 2/2 복구**됐다. 완결 서빙에서는 이 실패가 곧 영구 미노출이라 재시도가 필요하다. 재시도는 **전송 계층이 아니라 여기서만** 건다 — `JdkHikerHttp`의 "404 = 결정적 부재" 전제는 `by/username`·게시물 단건에서는 여전히 맞고, 거기까지 켜면 계정 삭제 판정이 그만큼 늦어진다.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`BrandCollectServiceTest.java`에 추가. 기존 fake `HikerHttp` 관용구를 쓴다 — 이 테스트 클래스는 `calls` 리스트에 호출 경로를 쌓고 응답 JSON을 맵으로 돌려주는 구조다:

```java
/**
 * 게시자 404는 결정적 부재가 아니다(08-13 실측 2%, 재시도 1회로 2/2 복구) — 1회 재시도해
 * 성공하면 프로필을 저장한다. 재시도가 없으면 완결 서빙에서 그 게시물이 영구 미노출이 된다.
 */
@Test
void 게시자_404는_1회_재시도한다() {
	InMemoryAuthors authors = new InMemoryAuthors();
	AtomicInteger authorAttempts = new AtomicInteger();
	// 첫 호출만 404, 두 번째는 정상 — 산발적 404의 재현.
	HikerHttp flaky = path -> {
		if (path.startsWith("/v2/user/by/id")) {
			if (authorAttempts.incrementAndGet() == 1) {
				throw new SubjectNotFoundException("Hiker 404");
			}
			return AUTHOR_JSON;
		}
		return respond(path);
	};

	BrandCollectService svc = serviceWith(flaky, authors);
	svc.enrich(brand(), List.of(post("CODE_A", RECENT, "201")));

	assertThat(authorAttempts.get()).isEqualTo(2);          // 재시도 1회
	assertThat(authors.upserted).containsExactly("201");    // 재시도로 복구
}

/**
 * 재시도해도 실패하면 게시자 없이 넘어간다 — 무한 재시도로 화면을 막지 않는다.
 * 미수집분은 게시자 stale 판정으로 다음 스윕이 백스톱한다.
 */
@Test
void 게시자_404가_재시도_후에도_실패하면_건너뛴다() {
	InMemoryAuthors authors = new InMemoryAuthors();
	AtomicInteger authorAttempts = new AtomicInteger();
	HikerHttp dead = path -> {
		if (path.startsWith("/v2/user/by/id")) {
			authorAttempts.incrementAndGet();
			throw new SubjectNotFoundException("Hiker 404");
		}
		return respond(path);
	};

	BrandCollectService svc = serviceWith(dead, authors);
	svc.enrich(brand(), List.of(post("CODE_A", RECENT, "201")));   // 예외가 새어나가면 안 된다

	assertThat(authorAttempts.get()).isEqualTo(2);   // 최초 1 + 재시도 1, 그 이상은 안 한다
	assertThat(authors.upserted).isEmpty();
}
```

> 이 테스트 클래스에 이미 있는 헬퍼(`brand()`, `post(...)`, `respond(path)`, `serviceWith(...)`, 상수 `AUTHOR_JSON`)의 정확한 이름은 파일을 열어 확인하고 맞출 것. 없으면 기존 테스트가 서비스를 조립하는 방식(`new BrandCollectService(client(), callContext, writer, snapshots, comments, tagged, authors, Runnable::run, 30, maxPostsPerSweep, 3, 30)`)을 그대로 따라 만든다.

- [ ] **Step 2: 테스트가 실패하는 것을 확인한다**

Run: `./gradlew :monitoring:test --tests "*BrandCollectServiceTest"`
Expected: FAIL — `expected: 2 but was: 1` (재시도가 없어 1회만 호출)

- [ ] **Step 3: 재시도를 구현한다**

`BrandCollectService.ensureAuthors`의 워커 태스크 본문을 교체한다. 기존:

```java
tasks.add(CompletableFuture.runAsync(() -> callContext.runScoped(brandId, () -> {
	try {
		authors.upsert(hiker.fetchAuthorProfile(id));
	} catch (RuntimeException e) {
		log.warn("게시자 프로필 수집 실패(격리) — user_id {}: {}", id, e.toString());
	}
}), enrichWorker));
```

교체 후:

```java
tasks.add(CompletableFuture.runAsync(() -> callContext.runScoped(brandId,
		() -> fetchAuthorWithRetry(id)), enrichWorker));
```

그리고 같은 클래스에 메서드를 추가한다:

```java
/**
 * 게시자 프로필 1건 — 404는 1회 재시도한다(08-13 실측: 실존 계정에서 2.0% 발생, 재시도
 * 복구율 2/2). 전송 계층은 404를 "결정적 부재"로 보고 즉시 전파하는데, /v2/user/by/id에
 * 한해 그 전제가 틀렸다. 다른 엔드포인트(by/username·게시물 단건)의 404는 여전히 결정적이라
 * 전송 계층을 건드리지 않고 여기서만 되쏜다.
 *
 * <p>타임아웃·5xx는 재시도하지 않는다 — 전송 계층이 이미 maxRetries를 태운 뒤이고, 실측상
 * 느린 콜은 3회 연속 16~21초로 전부 실패해 워커만 45초 묶었다.
 */
private void fetchAuthorWithRetry(String igUserId) {
	try {
		authors.upsert(hiker.fetchAuthorProfile(igUserId));
		return;
	} catch (SubjectNotFoundException e) {
		log.info("게시자 404 — user_id {} 1회 재시도", igUserId);
	} catch (RuntimeException e) {
		log.warn("게시자 프로필 수집 실패(격리) — user_id {}: {}", igUserId, e.toString());
		return;
	}
	try {
		authors.upsert(hiker.fetchAuthorProfile(igUserId));
	} catch (RuntimeException e) {
		log.warn("게시자 프로필 재시도 실패(격리) — user_id {}: {}", igUserId, e.toString());
	}
}
```

`SubjectNotFoundException` import를 추가한다: `import com.celfit.monitoring.hiker.SubjectNotFoundException;`

- [ ] **Step 4: 테스트가 통과하는 것을 확인한다**

Run: `./gradlew :monitoring:test --tests "*BrandCollectServiceTest"`
Expected: PASS (기존 테스트 포함 전부)

- [ ] **Step 5: 커밋**

```bash
git add monitoring/src/main/java/com/celfit/monitoring/service/BrandCollectService.java monitoring/src/test/java/com/celfit/monitoring/service/BrandCollectServiceTest.java
git commit -m "feat(monitoring): 게시자 프로필 404를 1회 재시도 — 실측상 결정적 부재가 아님"
```

---

### Task 3: 보강 완료 시 정산 마킹

**Files:**
- Modify: `monitoring/src/main/java/com/celfit/monitoring/service/BrandCollectService.java` (`enrich`)
- Test: `monitoring/src/test/java/com/celfit/monitoring/service/BrandCollectServiceTest.java`

**Interfaces:**
- Consumes: `TaggedPostRepository.markEnriched(long, Collection<String>, Instant)` (Task 1)
- Produces: `enrich(BrandRow, List<PostInfo>)`가 반환 직전 정산 마킹을 수행 — Task 4·5·6이 이 동작에 의존한다

- [ ] **Step 1: 실패하는 테스트를 쓴다**

```java
/**
 * 보강이 끝나면 그 게시물들을 정산 마킹한다 — was 목록 게이트의 입력이다.
 * 게시자·댓글이 실패해 비어 있어도 마킹한다("시도가 끝났다"는 뜻).
 */
@Test
void 보강이_끝나면_정산_마킹한다() {
	InMemoryTagged tagged = new InMemoryTagged();
	BrandCollectService svc = serviceWith(tagged);

	svc.enrich(brand(), List.of(post("CODE_A", RECENT, "201"), post("CODE_B", RECENT, "202")));

	assertThat(tagged.enriched).containsExactlyInAnyOrder("CODE_A", "CODE_B");
}

/** 게시자 수집이 전부 실패해도 정산은 진행된다 — 안 그러면 그 게시물이 영구 미노출이 된다. */
@Test
void 게시자_수집이_실패해도_정산한다() {
	InMemoryTagged tagged = new InMemoryTagged();
	HikerHttp dead = path -> {
		if (path.startsWith("/v2/user/by/id")) {
			throw new SubjectNotFoundException("Hiker 404");
		}
		return respond(path);
	};
	BrandCollectService svc = serviceWith(dead, tagged);

	svc.enrich(brand(), List.of(post("CODE_A", RECENT, "201")));

	assertThat(tagged.enriched).containsExactly("CODE_A");
}

/**
 * 댓글이 미완주(중간 페이지 실패)여도 정산한다 — 단, 워터마크는 전진시키지 않아
 * 다음 스윕이 못 받은 페이지를 재시도한다(현행 규칙 불변, 정산만 얹힌다).
 */
@Test
void 댓글_미완주여도_정산하되_워터마크는_전진하지_않는다() {
	InMemoryTagged tagged = new InMemoryTagged();
	AtomicInteger commentCalls = new AtomicInteger();
	HikerHttp partialComments = path -> {
		if (path.startsWith("/v2/media/comments")) {
			// 1페이지는 정상(다음 커서 있음), 2페이지에서 실패 → CommentsFetch.complete=false
			if (commentCalls.incrementAndGet() == 1) {
				return COMMENTS_PAGE_WITH_CURSOR_JSON;
			}
			throw new HikerFetchException("댓글 2페이지 실패");
		}
		return respond(path);
	};
	BrandCollectService svc = serviceWith(partialComments, tagged);

	svc.enrich(brand(), List.of(postWithComments("CODE_A", RECENT, "201", 30)));

	assertThat(tagged.enriched).containsExactly("CODE_A");
	assertThat(tagged.collectedCounts).doesNotContainKey("CODE_A");   // 워터마크 미전진
}
```

`COMMENTS_PAGE_WITH_CURSOR_JSON`은 이 테스트 클래스에 이미 있는 댓글 응답 상수에 다음 커서 필드를 넣은 변형이다 — 기존 댓글 게이트 테스트가 쓰는 상수를 찾아 그 셰이프를 그대로 따른다. `postWithComments(code, takenAt, ownerId, commentCount)`도 기존 `post(...)` 헬퍼의 `comment_count` 지정 변형이다.

`InMemoryTagged` 스텁에 수집용 필드와 오버라이드를 추가한다:

```java
final List<String> enriched = Collections.synchronizedList(new ArrayList<>());

@Override
public void markEnriched(long brandId, Collection<String> codes, Instant at) {
	enriched.addAll(codes);
}
```

- [ ] **Step 2: 테스트가 실패하는 것을 확인한다**

Run: `./gradlew :monitoring:test --tests "*BrandCollectServiceTest"`
Expected: FAIL — `enriched`가 비어 있음

- [ ] **Step 3: `enrich`에 정산 마킹을 넣는다**

기존:

```java
public void enrich(BrandRow brand, List<PostInfo> posts) {
	if (posts.isEmpty()) {
		return;
	}
	ensureAuthors(brand.id(), posts);
	collectCommentsGated(brand.id(), posts);
	log.info("브랜드 태그 보강 — {} 게시자·댓글 수집 완료({}건 대상)", brand.username(), posts.size());
}
```

교체 후:

```java
public void enrich(BrandRow brand, List<PostInfo> posts) {
	if (posts.isEmpty()) {
		return;
	}
	ensureAuthors(brand.id(), posts);
	collectCommentsGated(brand.id(), posts);
	// 정산 마킹(2026-08-13 스펙 §1) — 게시자·댓글이 실패해 비어 있어도 찍는다. 이 지점의 의미는
	// "더 기다릴 이유가 없다"이지 "다 찼다"가 아니다. 비운 채로 두면 실측 404 2%·타임아웃 1%의
	// 게시물이 목록에서 영구히 사라진다. 미수집분은 게시자 stale·댓글 워터마크가 다음 스윕에서 채운다.
	taggedPosts.markEnriched(brand.id(),
			posts.stream().map(PostInfo::shortCode).toList(), Instant.now());
	log.info("브랜드 태그 보강 — {} 게시자·댓글 수집 완료·정산({}건 대상)", brand.username(), posts.size());
}
```

- [ ] **Step 4: 테스트가 통과하는 것을 확인한다**

Run: `./gradlew :monitoring:test --tests "*BrandCollectServiceTest"`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add monitoring/src/main/java/com/celfit/monitoring/service/BrandCollectService.java monitoring/src/test/java/com/celfit/monitoring/service/BrandCollectServiceTest.java
git commit -m "feat(monitoring): 보강 완료 시 게시물 정산 마킹 — 실패해도 빈 채로 정산"
```

---

### Task 4: `sweepCore` 페이지 콜백화 + 서빙 창 설정 제거

**Files:**
- Modify: `monitoring/src/main/java/com/celfit/monitoring/service/BrandCollectService.java` (`sweepCore`·`doSweepCore`·생성자)
- Modify: `monitoring/src/main/resources/application.yml` (`serving-window-days` 삭제)
- Test: `monitoring/src/test/java/com/celfit/monitoring/service/BrandCollectServiceTest.java`

**Interfaces:**
- Consumes: `enrich(BrandRow, List<PostInfo>)`의 정산 동작 (Task 3)
- Produces: `sweepCore(BrandRow brand, Consumer<List<PostInfo>> onPageCollected)` — 콜백이 **페이지마다** 그 **페이지분만** 받는다(누적 아님). Task 5·6이 이 시그니처를 쓴다
- Produces: `BrandCollectService` 생성자에서 `servingWindowDays` 파라미터가 **사라진다**(12개 → 11개). 기존 테스트 조립부 전부 수정 필요

- [ ] **Step 1: 실패하는 테스트를 쓴다**

```java
/**
 * 콜백은 페이지마다 1회, 그 페이지분만 받는다(누적 아님) — 페이지 배치 방출의 전제다.
 * 구 계약은 "서빙 창 커버 시 1회 + 누적 리스트"였다.
 */
@Test
void 서빙_콜백이_페이지마다_그_페이지분만_받는다() {
	// 2페이지짜리 열거를 준비한다(각 페이지 2건, 전부 편입 컷 안).
	List<List<String>> batches = new ArrayList<>();
	BrandCollectService svc = serviceWithTwoPages();

	svc.sweepCore(brand(), page -> batches.add(page.stream().map(PostInfo::shortCode).toList()));

	assertThat(batches).hasSize(2);
	assertThat(batches.get(0)).containsExactly("P1_A", "P1_B");
	assertThat(batches.get(1)).containsExactly("P2_A", "P2_B");   // 1페이지분이 섞이지 않는다
}

/** 태그 0건 브랜드도 콜백을 1회 받는다 — 안 그러면 collecting에 영구히 갇힌다. */
@Test
void 태그가_0건이면_빈_페이지로_콜백을_1회_부른다() {
	AtomicInteger calls = new AtomicInteger();
	BrandCollectService svc = serviceWithNoTaggedPosts();

	svc.sweepCore(brand(), page -> calls.incrementAndGet());

	assertThat(calls.get()).isEqualTo(1);
}
```

- [ ] **Step 2: 테스트가 실패하는 것을 확인한다**

Run: `./gradlew :monitoring:test --tests "*BrandCollectServiceTest"`
Expected: FAIL — 콜백이 1회만 호출되고 누적 리스트가 온다

- [ ] **Step 3: `doSweepCore`를 페이지 콜백으로 바꾼다**

세 곳을 고친다.

(a) 생성자에서 `servingWindowDays` 파라미터와 필드를 제거한다:

```java
// 삭제: @Value("${monitoring.brand.serving-window-days:30}") int servingWindowDays,
// 삭제: private final int servingWindowDays;
// 삭제: this.servingWindowDays = servingWindowDays;
```

(b) `doSweepCore`에서 서빙 창 판정 블록을 제거하고 페이지 콜백을 넣는다. 삭제할 것:

```java
Instant servingCutoff = now.minus(Duration.ofDays(servingWindowDays));
boolean servingMarked = false;
...
if (!servingMarked && page.posts().stream().allMatch(p -> p.takenAt() != null
		&& Instant.ofEpochSecond(p.takenAt()).isBefore(servingCutoff))) {
	servingMarked = true;
	onServingCovered.accept(List.copyOf(collected));
}
...
if (!servingMarked) {
	onServingCovered.accept(List.copyOf(collected));
}
```

대신 `collected.addAll(...)` 한 줄(현행 `collected.addAll(processPage(brand, newItems, known, today, now));`)을 이렇게 바꾼다:

```java
List<PostInfo> pageCollected = processPage(brand, newItems, known, today, now);
collected.addAll(pageCollected);
freshTotal += known.size() - knownBefore;
// 페이지 배치 방출(2026-08-13 스펙 §2) — 이 페이지분을 즉시 콜백에 넘긴다. 수신자가 보강·정산을
// 끝내면 그때부터 was 목록에 뜬다. 누적이 아니라 페이지분만 넘기므로 수신자 쪽 중복 필터
// (구 earlyCodes)가 통째로 불필요해진다.
onPageCollected.accept(pageCollected);
anyPageDelivered = true;
```

기존 `freshTotal += known.size() - knownBefore;` 줄은 위로 흡수되므로 원래 자리에서 지운다.

(c) 루프 밖, `while` 종료 직후에 "한 번도 안 불렸으면 빈 페이지로 1회" 폴백을 넣는다:

```java
if (collected.isEmpty() && anyPageDelivered == false) {
	// 태그 0건이거나 첫 페이지가 편입 컷에 전부 걸린 경우 — 수신자(등록 백필)가 ready를 열 수
	// 있게 빈 배치로 1회 부른다. 안 부르면 태그가 없는 브랜드가 collecting에 영구히 갇힌다.
	onPageCollected.accept(List.of());
}
```

`anyPageDelivered`는 루프 진입 전에 `boolean anyPageDelivered = false;`로 선언하고 `onPageCollected.accept(pageCollected)` 직후 `anyPageDelivered = true;`로 세운다. (빈 `pageCollected`도 전달된 것으로 친다 — 수신자가 ready를 열 기회를 얻는 것이 목적이다.)

메서드 시그니처와 javadoc의 파라미터명을 `onServingCovered` → `onPageCollected`로 바꾸고, javadoc의 "정확히 1회 호출된다 / 누적 리스트" 서술을 "페이지마다 1회, 그 페이지분"으로 고친다.

(d) `application.yml`에서 설정 줄을 삭제한다:

```yaml
    serving-window-days: 30         # ← 이 줄 삭제(2026-08-13: 첫 페이지 배치 완료가 ready 기준이 됨)
```

`Duration` import가 다른 곳에서 안 쓰이면 함께 제거한다(Error Prone이 미사용 import를 잡지는 않지만 정리한다).

- [ ] **Step 4: 기존 테스트의 서비스 조립부를 고친다**

`BrandCollectServiceTest`에서 생성자 인자 12개 중 `servingWindowDays`(값 `30`, `authors` 다음 `Runnable::run` 뒤 첫 숫자)를 뺀다:

```java
// 변경 전
new BrandCollectService(client(), callContext, writer, snapshots, comments, tagged, authors,
		Runnable::run, 30, maxPostsPerSweep, 3, 30);
// 변경 후
new BrandCollectService(client(), callContext, writer, snapshots, comments, tagged, authors,
		Runnable::run, maxPostsPerSweep, 3, 30);
```

같은 파일 안 다른 조립부(예: 워커 풀 테스트의 `new BrandCollectService(latched, ...)`)도 같은 방식으로 고친다.

- [ ] **Step 5: 테스트가 통과하는 것을 확인한다**

Run: `./gradlew :monitoring:test --tests "*BrandCollectServiceTest"`
Expected: PASS

- [ ] **Step 6: 커밋**

```bash
git add monitoring/src/main/java/com/celfit/monitoring/service/BrandCollectService.java monitoring/src/main/resources/application.yml monitoring/src/test/java/com/celfit/monitoring/service/BrandCollectServiceTest.java
git commit -m "feat(monitoring): 열거 콜백을 페이지 단위로 전환 — 서빙 창 30일 기준 제거"
```

---

### Task 5: 등록 백필 배선 — 첫 배치 완료가 ready

**Files:**
- Modify: `monitoring/src/main/java/com/celfit/monitoring/service/BrandRegistrationService.java` (`runBackfillSafely`)
- Test: `monitoring/src/test/java/com/celfit/monitoring/service/BrandRegistrationServiceTest.java`

**Interfaces:**
- Consumes: `sweepCore(BrandRow, Consumer<List<PostInfo>>)` 페이지 콜백 (Task 4), `enrich`의 정산 (Task 3)
- Produces: 없음(말단 배선)

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`BrandRegistrationServiceTest.java`에 추가:

```java
/**
 * 첫 페이지 배치의 보강이 끝나는 시점에 ready를 연다(2026-08-13 스펙 §2) —
 * markServing이 열거 전체가 아니라 첫 배치 뒤에 딱 1회 불려야 한다.
 */
@Test
void 첫_페이지_배치_보강_후에_markServing을_1회_부른다() {
	// 2페이지 열거를 주는 스텁으로 등록 백필을 돌린다.
	service.register("brandx");

	assertThat(brands.servingMarks).isEqualTo(1);
	// markServing 시점에 첫 페이지분 보강이 이미 끝나 있어야 한다.
	assertThat(brands.enrichedCodesAtServingMark).containsExactly("P1_A", "P1_B");
}

/** last_swept_on은 완주 시점의 touchSwept 몫이다 — 첫 배치에서 찍으면 안 된다. */
@Test
void 첫_배치에서는_last_swept_on을_찍지_않는다() {
	service.register("brandx");

	assertThat(brands.sweptOnMarksBeforeFirstServing).isZero();
}
```

> 이 테스트 클래스의 기존 스텁 `BrandRepository`에 `servingMarks` 카운터가 없으면 추가한다. `enrichedCodesAtServingMark`는 `markServing` 호출 시점에 `InMemoryTagged.enriched`의 스냅샷을 복사해 담는다.

- [ ] **Step 2: 테스트가 실패하는 것을 확인한다**

Run: `./gradlew :monitoring:test --tests "*BrandRegistrationServiceTest"`
Expected: FAIL — 컴파일 실패 또는 `markServing` 시점에 보강이 안 끝나 있음

- [ ] **Step 3: `runBackfillSafely`를 다시 쓴다**

기존(누적 리스트 + `earlyCodes` 필터 방식) 전체를 교체한다:

```java
/**
 * 백필 core = 매일 스윕과 같은 열거·적재 코드(페이지 스트리밍). 2026-08-13 개정: 페이지마다
 * 그 페이지분을 <b>enrich 큐에 제출</b>하고 열거는 계속 앞서 달린다(파이프라인 — 열거 ~5초/페이지와
 * 보강 ~5.4초/페이지가 겹쳐 완주가 절반이 된다). <b>첫 제출분의 보강이 끝나는 지점에서
 * markServing</b>으로 FE ready를 연다(구 "서빙 창 30일 커버" 기준 대체).
 *
 * <p>touchSwept는 <b>모든 페이지 보강이 끝난 뒤</b>에 찍는다 — 이 값이 곧 응답
 * collectionCompletedAt이고 FE의 폴링 종료 조건이라, 아직 정산 안 된 페이지가 남은 채로 찍으면
 * FE가 미완성 목록을 최종본으로 알고 폴링을 멈춘다. 열거 완주 ≠ 수집 완주로 의미가 갈렸다.
 *
 * <p>core 실패는 격리 — 이미 정산된 페이지는 서빙을 유지하고, 다음 스윕이 잔여를 백스톱한다.
 */
private void runBackfillSafely(BrandRow row) {
	try {
		AtomicBoolean served = new AtomicBoolean();
		List<CompletableFuture<Void>> pages = new ArrayList<>();
		collect.sweepCore(row, page -> pages.add(CompletableFuture.runAsync(() -> {
			runEnrichSafely(row, page);
			// 첫 완료분이 ready를 연다. 페이지 순서가 아니라 완료 순서인 것은 무해하다 —
			// 목록 정렬은 taken_at이고 markServing은 last_swept_at IS NULL 가드로 1회만 먹는다.
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
		// markServing 이후 실패면 ready가 이미 열려 있고(정산된 페이지 서빙) 이 문구는 FE에서 무시된다.
		brands.markBackfillError(row.id(), "초기 수집에 실패했어요. 자동으로 재시도 중이에요.");
	}
}
```

import를 정리한다: `java.util.concurrent.CompletableFuture`·`java.util.concurrent.atomic.AtomicBoolean`·`java.util.ArrayList` 추가, 안 쓰이게 된 `java.util.HashSet`·`java.util.Set` 제거.

> **교착 없음 확인:** `join()`은 backfill core 스레드에서 걸리고 태스크는 enrich executor에서 돈다 — 서로 다른 풀이다. `collect.enrich` 안의 `join()`도 enrich executor 스레드에서 걸려 `brandEnrichWorkerPool`을 기다린다(역시 별도 풀). 세 층이 전부 다른 풀이어야 하며, 이 중 둘을 합치면 영구 자기 교착이다.

> **큐 적체:** 열거가 앞서 달리므로 enrich 큐에 페이지가 쌓인다. 08-12 OOM과 같은 형태지만 `PostInfo.rawJson` 제거 이후 페이지당 수십 KB라(구 1.7MB) 상한 10,000건 브랜드에서도 ~20MB 수준이다. 페이지 리스트를 큐에 넣는 것 외에 다른 것을 붙들지 않도록 주의한다.

- [ ] **Step 3-1: `touchSwept` 시점을 검증하는 테스트를 추가한다**

```java
/**
 * 완주 시각(= 응답 collectionCompletedAt, FE 폴링 종료 조건)은 모든 페이지 보강이 끝난 뒤에
 * 찍힌다 — 열거 완주 시점에 찍으면 FE가 미완성 목록을 최종본으로 알고 폴링을 멈춘다.
 */
@Test
void 모든_페이지_보강이_끝난_뒤에_touchSwept한다() {
	service.register("brandx");

	assertThat(brands.enrichedCodesAtTouchSwept)
			.containsExactlyInAnyOrder("P1_A", "P1_B", "P2_A", "P2_B");
}
```

(`enrichedCodesAtTouchSwept`는 스텁 `BrandRepository.touchSwept`가 불릴 때 `InMemoryTagged.enriched`를 복사해 담는 필드다.)

- [ ] **Step 4: 테스트가 통과하는 것을 확인한다**

Run: `./gradlew :monitoring:test --tests "*BrandRegistrationServiceTest"`
Expected: PASS

- [ ] **Step 5: monitoring 전체 테스트를 돌린다**

Run: `./gradlew :monitoring:test`
Expected: PASS (기존 테스트 포함)

- [ ] **Step 6: 커밋**

```bash
git add monitoring/src/main/java/com/celfit/monitoring/service/BrandRegistrationService.java monitoring/src/test/java/com/celfit/monitoring/service/BrandRegistrationServiceTest.java
git commit -m "feat(monitoring): 등록 백필을 페이지 배치로 — 첫 배치 완결이 ready 기준"
```

---

### Task 6: 일일 스윕 통일 + 동시성 설정

**Files:**
- Modify: `monitoring/src/main/java/com/celfit/monitoring/service/BrandCollectService.java` (`sweep`)
- Modify: `monitoring/src/main/java/com/celfit/monitoring/config/BrandBackfillConfig.java`
- Modify: `monitoring/src/main/resources/application.yml`
- Test: `monitoring/src/test/java/com/celfit/monitoring/service/BrandCollectServiceTest.java`

**Interfaces:**
- Consumes: `sweepCore(BrandRow, Consumer<List<PostInfo>>)` (Task 4)
- Produces: 설정 키 `monitoring.brand.enrich-executor-concurrency`(신규, 기본 2)

- [ ] **Step 1: 실패하는 테스트를 쓴다**

"전량을 보강한다"만 보면 현행 `sweep`도 통과한다(결국 다 보강하므로). **정산 시점**을 보는 테스트여야 실패한다 — 2페이지 열거에서 두 번째 페이지 콜이 실패해도 1페이지분이 이미 정산돼 있는지 본다.

```java
/**
 * 일일 스윕도 페이지 배치로 돈다(2026-08-13 스펙 §3) — 열거 전량 후 일괄 보강을 유지하면
 * 중간 실패 시 그 스윕에서 만난 게시물이 통째로 미정산(= 목록 미노출)으로 남는다.
 */
@Test
void 스윕_중간_실패해도_앞_페이지는_정산된다() {
	InMemoryTagged tagged = new InMemoryTagged();
	// 1페이지는 정상, 2페이지 열거 콜에서 예외.
	BrandCollectService svc = serviceWithSecondPageFailing(tagged);

	assertThatThrownBy(() -> svc.sweep(brand())).isInstanceOf(HikerFetchException.class);

	assertThat(tagged.enriched).containsExactlyInAnyOrder("P1_A", "P1_B");
}
```

`serviceWithSecondPageFailing`은 이 파일의 fake `HikerHttp` 관용구로 만든다 — `/v2/user/tag/medias` 호출 횟수를 세어 2회째에 `throw new HikerFetchException("2페이지 실패")`.

- [ ] **Step 2: 테스트가 실패하는 것을 확인한다**

Run: `./gradlew :monitoring:test --tests "*BrandCollectServiceTest"`
Expected: FAIL — 현행 `sweep`은 열거를 다 끝낸 뒤 보강하므로 중간 실패 시 정산이 0건

- [ ] **Step 3: `sweep`을 페이지 배치로 바꾼다**

```java
/**
 * 브랜드 1개분 전량 수집(매일 스윕 경로) — 2026-08-13 개정: 열거 전량 후 일괄 보강이 아니라
 * <b>페이지마다 보강·정산</b>한다. 완결 서빙 규칙(정산된 게시물만 노출) 아래서 일괄 보강을
 * 유지하면, 스윕이 도는 동안 새로 적재된 게시물이 목록에서 사라지고 중간 실패 시 그 스윕에서
 * 만난 게시물이 통째로 미노출로 남는다.
 */
public void sweep(BrandRow brand) {
	sweepCore(brand, page -> enrich(brand, page));
}
```

- [ ] **Step 4: 테스트가 통과하는 것을 확인한다**

Run: `./gradlew :monitoring:test --tests "*BrandCollectServiceTest"`
Expected: PASS

- [ ] **Step 5: enrich executor를 2스레드로, 워커를 10으로 올린다**

`BrandBackfillConfig.java`:

```java
/**
 * 보강 큐 — 2026-08-13부터 동시 2스레드(설정 {@code monitoring.brand.enrich-executor-concurrency}).
 * 단일 스레드였을 때는 core가 2병렬인데 보강이 1이라, 연속 등록 시 둘째 브랜드의 보강이 첫
 * 브랜드 완주를 통째로 기다렸다. 완결 배치 서빙 이전에는 markServing이 열거만으로 ready를
 * 열어줘 이 줄이 안 보였지만, 이제는 둘째 브랜드의 화면이 그대로 빈다.
 *
 * <p>같은 개정으로 제출 단위가 "브랜드 1건 = 큰 태스크 1개"에서 "페이지 1건 = 작은 태스크
 * 다수"로 바뀌었다. FIFO 큐에서 잔 태스크가 섞이므로 뒤 브랜드가 앞 브랜드 완주 전체를
 * 기다리지 않고 사이사이 진행된다.
 *
 * <p>Hiker 동시 콜 예산은 늘지 않는다 — 콜은 전부 공유 워커 풀(brandEnrichWorkerPool)을
 * 통해 나가고, 이 executor는 그 풀을 두 브랜드가 나눠 쓰게 할 뿐이다.
 */
@Bean(name = "brandEnrichExecutor")
public Executor brandEnrichExecutor(
		@Value("${monitoring.brand.enrich-executor-concurrency:2}") int concurrency) {
	AtomicInteger seq = new AtomicInteger();
	return Executors.newFixedThreadPool(concurrency, r -> {
		Thread t = new Thread(r, "brand-enrich-" + seq.incrementAndGet());
		t.setDaemon(true);
		return t;
	});
}
```

`application.yml`:

```yaml
    enrich-concurrency: 10          # 보강(게시자·댓글) 워커 풀 — 08-13 실측에서 6/8/10 어느 레벨도 5초 초과 콜 미증가.
                                    # executor 2병렬로 브랜드당 실효 워커가 반감하므로 그 상쇄분이기도 하다.
    enrich-executor-concurrency: 2  # 동시 보강 브랜드 수(08-13) — core 2병렬과 짝. 전역 동시 콜은 워커 풀이 상한이라 안 늘어난다
```

`BrandBackfillConfig`의 클래스 javadoc에서 "전역 동시 콜 최대 9(워커 6 + 스윕 core 1 + 등록 core 2)"를 **13(워커 10 + 1 + 2)**으로 고치고, 근거로 08-13 실측을 덧붙인다.

- [ ] **Step 6: monitoring 전체 테스트**

Run: `./gradlew :monitoring:test`
Expected: PASS

- [ ] **Step 7: 커밋**

```bash
git add monitoring/src/main/java/com/celfit/monitoring/service/BrandCollectService.java monitoring/src/main/java/com/celfit/monitoring/config/BrandBackfillConfig.java monitoring/src/main/resources/application.yml monitoring/src/test/java/com/celfit/monitoring/service/BrandCollectServiceTest.java
git commit -m "feat(monitoring): 일일 스윕도 페이지 배치로 통일 + 보강 동시성 상향(executor 2·워커 10)"
```

---

### Task 7: 확장 시 완주 시각 리셋 + was 유도 분기 정리

**Files:**
- Modify: `monitoring/src/main/java/com/celfit/monitoring/store/BrandRepository.java` (`expandWindow`)
- Modify: `was/src/main/java/com/celfit/was/v1/brandmonitoring/BrandAccountAssembler.java`
- Test: `monitoring/src/test/java/com/celfit/monitoring/store/BrandStoreTest.java`
- Test: `was/src/test/java/com/celfit/was/v1/brandmonitoring/BrandAccountAssemblerTest.java`

**Interfaces:**
- Consumes: 없음
- Produces: 확장 중 계정의 응답이 `collectionStatus="ready"` + `collectionCompletedAt=null`

- [ ] **Step 1: 실패하는 테스트를 쓴다 (monitoring)**

`BrandStoreTest.java`:

```java
/**
 * 기간 확장은 완주 시각도 리셋한다(2026-08-13) — FE 폴링 종료 조건이
 * collectionCompletedAt != null이라, 보존하면 확장 시작 즉시 폴링이 멎는다.
 */
@Test
void 기간_확장이_완주_시각을_리셋한다() {
	long id = brands.insertOrReactivate("brandx", profile(), 3);
	brands.touchSwept(id, LocalDate.now());
	assertThat(completedAt(id)).isNotNull();

	assertThat(brands.expandWindow(id, 12)).isTrue();

	assertThat(completedAt(id)).isNull();
}
```

(`completedAt(long)`은 `SELECT backfill_completed_at FROM brand_account WHERE id = ?`를 돌리는 헬퍼. 이 테스트 클래스의 기존 조회 관용구를 따른다.)

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :monitoring:test --tests "*BrandStoreTest"`
Expected: FAIL — `expected: null but was: <timestamp>`

- [ ] **Step 3: `expandWindow`를 고친다**

```java
public boolean expandWindow(long brandId, int months) {
	return db.update("""
			UPDATE brand_account
			SET collection_months = GREATEST(collection_months, ?), last_swept_on = NULL,
			    collection_started_at = now(), backfill_error = NULL,
			    backfill_completed_at = NULL
			WHERE id = ? AND collection_months < ?""", months, brandId, months) > 0;
}
```

javadoc의 "backfill_completed_at은 보존한다 — was가 …확장 중으로 collecting을 유도하는 판별 재료다" 문단을 교체한다:

```java
 * <p>2026-08-13 개정: backfill_completed_at도 리셋한다. FE 폴링 종료 조건이 이 값(응답
 * collectionCompletedAt)이 되면서, 보존하면 확장 시작 즉시 폴링이 멎어 확장분이 화면에
 * 반영되지 않는다. 그 대가로 was의 "확장 중 → collecting" 유도 분기가 도달 불가가 되어
 * 함께 제거했다 — 확장 중 상태는 ready이고, 진행 여부는 FE가 collectionCompletedAt == null로
 * 판정한다(status 값 공간을 3값으로 고정해달라는 FE 요청 계약).
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew :monitoring:test --tests "*BrandStoreTest"`
Expected: PASS

- [ ] **Step 5: was 조립기 테스트를 쓴다**

`BrandAccountAssemblerTest.java`:

```java
/**
 * 확장 중(last_swept_on null · backfill_completed_at null · last_swept_at 있음)은 ready다
 * (2026-08-13) — 08-12의 "확장 중 collecting" 분기는 완주 시각 리셋으로 도달 불가가 됐다.
 * 진행 여부 판정은 FE가 collectionCompletedAt == null로 한다.
 */
@Test
void 확장_중_계정은_ready다() {
	var row = rowWith(null, null, OffsetDateTime.now());   // lastSweptOn, backfillCompletedAt, lastSweptAt

	var response = assembler.toResponse(row, "own");

	assertThat(response.collectionStatus()).isEqualTo("ready");
	assertThat(response.collectionCompletedAt()).isNull();
}
```

(`rowWith(...)`는 이 테스트 클래스의 기존 `BrandAccountRow` 생성 헬퍼를 따른다.)

- [ ] **Step 6: 실패 확인**

Run: `./gradlew :was:test --tests "*BrandAccountAssemblerTest"`
Expected: 이미 통과할 수 있다(3번 분기가 잡음). 그렇다면 이 테스트는 **회귀 방지 고정**이고, 다음 스텝의 분기 제거 뒤에도 통과해야 한다

- [ ] **Step 7: 도달 불가 분기를 제거한다**

`BrandAccountAssembler.toResponse`에서 두 번째 분기를 삭제한다:

```java
// 삭제
} else if (row.backfillCompletedAt() != null) {
	status = STATUS_COLLECTING;
```

클래스 javadoc의 상태 유도 규칙 목록에서 해당 줄을 지우고, 08-12 문단을 08-13 개정으로 교체한다:

```java
 *   <li>{@code last_swept_on} 있음(이번 창 기준 완주) → {@code ready}</li>
 *   <li>{@code last_swept_at} 있음 → {@code ready}(첫 등록 배치 완결·재가입·기간 확장 중)</li>
 *   <li>전부 null + {@code backfill_error} 있음 → {@code error} + collectionError, 아니면 {@code collecting}</li>
```

```java
 * <p>2026-08-13 개정: 08-12에 넣었던 "확장 중 → collecting" 분기를 제거했다. 확장이
 * backfill_completed_at을 리셋하게 되면서 그 분기의 조건(완주 이력 있음 + last_swept_on 빔)이
 * 도달 불가가 됐다. FE 계약상 collectionStatus는 collecting|ready|error 3값 고정이고,
 * 수집 진행 여부는 collectionCompletedAt == null로 판정한다.
```

- [ ] **Step 8: was 테스트 통과 확인**

Run: `./gradlew :was:test --tests "*BrandAccountAssembler*"`
Expected: PASS. 기존 테스트 중 "확장 중이면 collecting"을 검증하던 케이스가 있으면 위 새 기대값으로 고친다

- [ ] **Step 9: 커밋**

```bash
git add monitoring/src/main/java/com/celfit/monitoring/store/BrandRepository.java monitoring/src/test/java/com/celfit/monitoring/store/BrandStoreTest.java was/src/main/java/com/celfit/was/v1/brandmonitoring/BrandAccountAssembler.java was/src/test/java/com/celfit/was/v1/brandmonitoring/BrandAccountAssemblerTest.java
git commit -m "feat(monitoring,was): 기간 확장이 완주 시각을 리셋 — was 확장 분기 제거로 status 3값 고정"
```

---

### Task 8: was 목록 게이트

**Files:**
- Modify: `was/src/main/java/com/celfit/was/monitoring/BrandReadRepository.java` (`findTaggedPostsInWindow`)
- Modify: `was/src/test/resources/monitoring-brand-schema.sql`
- Test: `was/src/test/java/com/celfit/was/monitoring/BrandReadRepositoryTest.java`

**Interfaces:**
- Consumes: `brand_tagged_post.enriched_at` (Task 1)
- Produces: 목록·상세·`meta.counts` 전부가 정산분만 본다(모두 이 메서드 하나를 경유)

- [ ] **Step 1: 테스트 스키마 미러에 컬럼을 추가한다**

`monitoring-brand-schema.sql`의 `brand_tagged_post` 정의에 운영과 **동일하게**(nullable, 기본값 없음) 추가한다:

```sql
CREATE TABLE IF NOT EXISTS brand_tagged_post (
    brand_id                 bigint      NOT NULL REFERENCES brand_account (id),
    short_code               text        NOT NULL,
    author_username          text        NOT NULL,
    author_ig_user_id        text,
    taken_at                 timestamptz NOT NULL,
    first_seen_at            timestamptz NOT NULL DEFAULT now(),
    comments_collected_count bigint      NOT NULL DEFAULT 0,
    enriched_at              timestamptz,
    PRIMARY KEY (brand_id, short_code)
);
```

기본값을 넣지 않는다 — 미러가 운영 DDL과 어긋나면 "테스트는 되는데 운영은 안 되는" 종류의 결함이 생긴다. 대신 픽스처가 값을 명시한다.

- [ ] **Step 2: 픽스처를 고치고 실패하는 테스트를 쓴다**

`BrandReadRepositoryTest.java`의 기존 삽입 헬퍼에 `enriched_at`을 넣는다(기존 케이스는 전부 정산분으로 취급):

```java
jdbc.sql("""
		INSERT INTO brand_tagged_post (brand_id, short_code, author_username, author_ig_user_id,
		                               taken_at, comments_collected_count, enriched_at)
		VALUES (:brandId, :shortCode, 'influencer_a', 'IG_A', :takenAt::timestamptz, 7, now())
		""")
		.param("brandId", brandId).param("shortCode", shortCode).param("takenAt", takenAt)
		.update();
```

그리고 미정산 게시물용 헬퍼와 테스트를 추가한다:

```java
private void insertUnenrichedTaggedPost(long brandId, String shortCode, String takenAt) {
	jdbc.sql("""
			INSERT INTO brand_tagged_post (brand_id, short_code, author_username, author_ig_user_id,
			                               taken_at, comments_collected_count, enriched_at)
			VALUES (:brandId, :shortCode, 'influencer_a', 'IG_A', :takenAt::timestamptz, 0, NULL)
			""")
			.param("brandId", brandId).param("shortCode", shortCode).param("takenAt", takenAt)
			.update();
}

/**
 * 보강 정산 전 게시물은 목록에 노출되지 않는다(2026-08-13 완결 배치 서빙) —
 * 게시자·댓글이 붙기 전의 반쯤 빈 카드를 FE에 내보내지 않기 위한 게이트다.
 */
@Test
void 정산되지_않은_게시물은_목록에서_제외된다() {
	long brandId = insertBrand("brandx");
	insertTaggedPost(brandId, "DONE_A", "2026-08-10T00:00:00+09:00");
	insertUnenrichedTaggedPost(brandId, "PENDING_B", "2026-08-11T00:00:00+09:00");

	var rows = repository.findTaggedPostsInWindow(brandId,
			OffsetDateTime.parse("2026-01-01T00:00:00+09:00"));

	assertThat(rows).extracting(BrandReadRepository.BrandTaggedPostRow::shortCode)
			.containsExactly("DONE_A");
}
```

(`insertBrand`·`insertTaggedPost`·`repository` 이름은 이 테스트 클래스의 기존 것을 그대로 쓴다.)

- [ ] **Step 3: 실패 확인**

Run: `./gradlew :was:test --tests "*BrandReadRepositoryTest"`
Expected: FAIL — `PENDING_B`가 함께 조회됨

- [ ] **Step 4: 게이트를 넣는다**

```java
/**
 * 브랜드 윈도우 안 태그 게시물 — cutoff(수집 창 컷) 이후 taken_at, 최신순 전량.
 *
 * <p>2026-08-13부터 <b>보강 정산분만</b>(enriched_at IS NOT NULL) 돌려준다 — 게시자 프로필·
 * 댓글이 붙기 전의 게시물을 FE에 내보내지 않는다는 계약이다(완결 배치 서빙 스펙 §5).
 * 정산은 "보강 시도가 끝났다"는 뜻이라, 게시자 조회가 실패한 게시물은 그 필드가 빈 채로
 * 노출된다 — FE의 빈 필드 방어는 계속 필요하다.
 */
public List<BrandTaggedPostRow> findTaggedPostsInWindow(long brandId, OffsetDateTime cutoff) {
	return jdbc.sql("""
			SELECT short_code, author_username, author_ig_user_id, taken_at, first_seen_at,
			       comments_collected_count
			FROM brand_tagged_post
			WHERE brand_id = :brandId AND taken_at >= :cutoff AND enriched_at IS NOT NULL
			ORDER BY taken_at DESC
			""")
			.param("brandId", brandId)
			.param("cutoff", cutoff)
			.query(BrandTaggedPostRow.class)
			.list();
}
```

- [ ] **Step 5: 통과 확인**

Run: `./gradlew :was:test --tests "*BrandReadRepositoryTest"`
Expected: PASS

- [ ] **Step 6: was 전체 테스트**

Run: `./gradlew :was:test`
Expected: PASS. `AdminCrawlingUsageIntegrationTest`가 `brand_tagged_post`를 참조하므로 함께 확인한다 — 거기서 게시물 목록을 기대하는 케이스가 있으면 픽스처에 `enriched_at`을 채운다

- [ ] **Step 7: 커밋**

```bash
git add was/src/main/java/com/celfit/was/monitoring/BrandReadRepository.java was/src/test/resources/monitoring-brand-schema.sql was/src/test/java/com/celfit/was/monitoring/BrandReadRepositoryTest.java
git commit -m "feat(was): 게시물 목록을 보강 정산분으로 제한 — 완결 배치 서빙 계약"
```

---

### Task 9: 문서 갱신 + 전체 회귀

**Files:**
- Modify: `DECISIONS.md` (맨 위에 행 추가)
- Modify: `docs/tracks/MON-BT-브랜드-태그-모니터링.md` (설계 문단을 구현 완료로 갱신)
- Modify: `docs/superpowers/specs/2026-08-13-brand-initial-batch-serving-design.md` (상태 헤더)
- Move: `docs/superpowers/plans/2026-08-13-brand-initial-batch-serving.md` → `plans/archive/`

- [ ] **Step 1: 전체 테스트를 돌린다**

```bash
./gradlew test
```

Expected: PASS. **PR 직전에만** 돌린다(모듈 4개가 각자 Testcontainers Postgres를 띄워 로컬 자원을 경합한다).

- [ ] **Step 2: 스펙 상태 헤더를 갱신한다**

```markdown
> 상태: 🟢 활성 · ✅ 구현됨 · 구현 계획: [2026-08-13-brand-initial-batch-serving.md](../plans/archive/2026-08-13-brand-initial-batch-serving.md)
```

- [ ] **Step 3: `DECISIONS.md` 맨 위에 행을 추가한다**

기존 행들의 형식(날짜 · 굵은 제목 · 결정 요약 · 근거 · 파급 · 코드 포인터)을 그대로 따른다. 반드시 담을 것:

- 서빙 판정이 "열거 적재됨" → "게시물 단위 보강 정산"(`enriched_at`)으로 이동
- 08-12의 서빙 창 30일 기준(`serving-window-days`) **폐기**
- **08-12 결정 뒤집기 명시**: 확장 중 상태가 `collecting` → `ready`(완주 시각 리셋에 따른 도달 불가 분기 제거)
- 실측 근거: 첫 페이지 완결 ~10초 · 댓글 p50 1.5초(08-07 "미실측 한계" 해소) · `by/id` 404율 2%·재시도 복구 2/2 · 워커 6/8/10에서 꼬리 미증가
- 기존 25,759행 백필이 필수였다는 점과 배포 순서(monitoring → was)

- [ ] **Step 4: 트랙 문서의 08-13 문단을 구현 완료로 갱신한다**

`docs/tracks/MON-BT-브랜드-태그-모니터링.md`의 "완결 배치 서빙(2026-08-13 — **설계 합의만, 미구현**…)" 머리말에서 미구현 표기를 지우고 커밋 해시를 단다.

- [ ] **Step 5: 계획 문서를 아카이브로 옮긴다**

```bash
git mv docs/superpowers/plans/2026-08-13-brand-initial-batch-serving.md docs/superpowers/plans/archive/
```

- [ ] **Step 6: 커밋하고 PR을 연다**

```bash
git add -A
git commit -m "docs: 완결 배치 서빙 구현 반영 — DECISIONS·트랙·스펙 상태 갱신, 계획 아카이브"
gh pr create --base develop --title "feat: 브랜드 초기 수집 완결 배치 서빙" --body "$(cat <<'EOF'
## 무엇

게시물 목록에 **보강 정산이 끝난 게시물만** 노출하고, 첫 열거 페이지(21건) 배치 완결 시점에
FE ready를 연다. FE 요청서(2026-08-13)의 계약 변경 대응.

설계: docs/superpowers/specs/2026-08-13-brand-initial-batch-serving-design.md

## 주요 변경

- `brand_tagged_post.enriched_at` 신설 + 기존 25,759행 백필 (마이그레이션)
- 열거 콜백을 페이지 단위로 전환, `serving-window-days`(서빙 창 30일) 제거
- 게시자 `by/id` 404를 1회 재시도 (실측: 실존 계정에서 2.0% 발생, 복구율 2/2)
- 보강 동시성 상향: enrich executor 1→2, 워커 6→10
- 기간 확장이 `backfill_completed_at`을 리셋 → was 확장 분기 제거

## ⚠ FE 배포 조율 필요

**기간 확장 중 `collectionStatus`가 `collecting` → `ready`로 바뀝니다.** FE가 확장 배너
판정을 `collectionCompletedAt == null`로 옮기기 전에 이게 배포되면 배너가 조용히 사라집니다.
FE 회신 문서 §3-7로 통지했고, 프론트 반영 여부를 확인한 뒤 운영 승격할 것.

## 배포 순서

monitoring → was. 역순이면 was가 없는 컬럼을 조회해 브랜드 목록이 전면 500.
운영 반영 직후 `SELECT count(*) FROM brand_tagged_post WHERE enriched_at IS NULL`이 0인지 확인.

## 테스트

- `./gradlew test` 전체 그린
EOF
)"
```

---

## 배포 절차 (머지 후)

1. develop → staging 머지 = test 스테이징 배포. `dev-api.hypenow.io`에서 브랜드 1건 등록해 **첫 화면이 ~10초에 뜨고 게시물이 21건인지** 확인한다
2. 스테이징 검증 후 staging → main 머지 = 운영 배포
3. **운영 배포 직후 확인**: `SELECT count(*) FROM brand_tagged_post WHERE enriched_at IS NULL` — 마이그레이션 직후에는 0이어야 한다. 0이 아니면 백필이 안 돈 것이고, 그만큼의 게시물이 목록에서 사라진 상태다
4. 배포는 CD로만 한다. `deploy/scripts/deploy.sh` 수동 실행 금지

## 검증 체크리스트

- [ ] `./gradlew :monitoring:test` 그린
- [ ] `./gradlew :was:test` 그린
- [ ] `./gradlew test` 전체 그린 (PR 직전 1회)
- [ ] 마이그레이션 채번이 UTC이고 `V20260812220000`보다 크다
- [ ] 마이그레이션에 기존 행 백필 UPDATE가 들어 있다
- [ ] `serving-window-days` 설정과 참조 코드가 남아 있지 않다 (`grep -rn "serving-window-days\|servingWindow" --exclude-dir=docs --exclude-dir=build .`)
- [ ] `BrandAccountAssembler`에 `STATUS_COLLECTING`을 세우는 분기가 마지막 하나만 남았다
