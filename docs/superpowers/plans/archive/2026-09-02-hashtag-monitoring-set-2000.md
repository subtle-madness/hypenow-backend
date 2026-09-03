# 해시태그 감시 세트 2,000 롤링 전환 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

> 상태: 🟢 활성 · 스펙: [2026-09-02-hashtag-monitoring-set-2000-design.md](../specs/archive/2026-09-02-hashtag-monitoring-set-2000-design.md)

**Goal:** 해시태그 편입을 1,000 하드스톱(신규 드랍)에서 "게시일 최신 2,000 롤링 감시 세트"로 전환하고, 2단계 단건 재수집이 세트 전체를 정책대로 갱신하게 하며, was 노출 상한을 폐지한다.

**Architecture:** 스키마 변경 없음(마이그레이션 0건). 세트 경계는 `TaggedPostRepository`의 "n번째 최신 해시태그 행 taken_at"(floor) 쿼리 하나로 정의하고, 편입(BrandHashtagCollectService)·재수집(BrandDirectCollectService)이 같은 floor를 공유한다. 세트 밖으로 밀려난 행은 tagged의 "커버 간주 touch" 관용구와 동형으로 매 스윕 touch(동결)한다.

**Tech Stack:** Java 21 / Spring Boot 4.1 / JdbcTemplate / JUnit5(서비스는 인메모리 스텁, store는 Testcontainers) / Grafana provisioning JSON.

## Global Constraints

- 이 머신 로컬 도커는 **Docker Desktop**이다 — `DOCKER_HOST`를 **설정하지 않는 것**이 정답(08-09 확인, CLAUDE.md의 colima 항목은 이 머신에 해당 없음).
- 테스트는 모듈 단위: `./gradlew :monitoring:test`, `./gradlew :was:test`. 전체 `./gradlew test`는 PR 직전에만.
- 커밋 메시지는 한국어, prefix `feat(monitoring):` / `feat(was):` / `fix(grafana):` / `docs:` 식. 커밋 말미에 `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`.
- 주석은 한국어, 기존 파일의 주석 밀도·관용구를 따른다.
- 스키마 변경 금지(이 계획은 DDL 0건이 정상이다) — DDL이 필요해 보이면 설계 오독이니 멈추고 확인.
- `grep` 시 `--exclude-dir=docs` (한글 문서 3.9MB가 코드 히트를 묻는다).

---

### Task 1: TaggedPostRepository — 감시 세트 경계 헬퍼 3종

**Files:**
- Modify: `monitoring/src/main/java/com/celfit/monitoring/store/TaggedPostRepository.java`
- Test: `monitoring/src/test/java/com/celfit/monitoring/store/TaggedPostHashtagSourceTest.java`

**Interfaces:**
- Produces: `Optional<Instant> nthNewestHashtagTakenAt(long brandId, int n)` — 해시태그 성분 행 중 n번째 최신 taken_at(행이 n개 미만이면 empty). Task 2·3이 세트 바닥(floor)으로 쓴다.
- Produces: `List<TrackedPost> unenumeratedDuePosts(long brandId, Instant minTakenAt, Instant hashtagFloor)` — 기존 2-인자와 같되 hashtag 성분 행을 `taken_at >= hashtagFloor`로 한정(`hashtagFloor` null이면 기존과 동일). direct 행은 floor 무관하게 항상 포함. 기존 2-인자 메서드는 3-인자에 null 위임으로 유지(기존 호출부·테스트 무수정).
- Produces: `void touchFrozenHashtag(long brandId, Instant floorTakenAt, Instant at)` — floor보다 오래된 해시태그 성분 행(direct 아님)의 `last_crawled_at`을 at으로 touch(커버 간주 동결 — tagged의 `touchCrawledDepth` 동형). 이미 at 이후로 찍힌 행은 되감지 않는다.

- [ ] **Step 1: 실패하는 테스트 작성** — `TaggedPostHashtagSourceTest`에 아래 3개 테스트 추가(기존 `post()` 픽스처·`repo`·`brandId` 재사용):

```java
	@Test
	void nthNewestHashtagTakenAt은_해시태그_성분_행만_센다() {
		repo.insert(brandId, post("TAGONLY", "poster1", NOW.minusSeconds(100)));  // tagged-only — 순위 밖
		repo.upsertHashtag(brandId, post("H1", "poster1", NOW.minusSeconds(1000)), NOW);
		repo.upsertHashtag(brandId, post("H2", "poster1", NOW.minusSeconds(2000)), NOW);
		repo.upsertHashtag(brandId, post("H3", "poster1", NOW.minusSeconds(3000)), NOW);

		assertThat(repo.nthNewestHashtagTakenAt(brandId, 2)).contains(NOW.minusSeconds(2000));
		assertThat(repo.nthNewestHashtagTakenAt(brandId, 4)).isEmpty();   // 3행뿐 — 세트 미포화
		assertThat(repo.nthNewestHashtagTakenAt(brandId, 0)).isEmpty();
	}

	@Test
	void unenumeratedDuePosts_floor는_해시태그만_자르고_direct는_남긴다() {
		repo.upsertHashtag(brandId, post("H_IN", "poster1", NOW.minusSeconds(1000)), NOW);
		repo.upsertHashtag(brandId, post("H_OUT", "poster1", NOW.minusSeconds(5000)), NOW);
		repo.upsertDirect(brandId, post("D_OLD", "poster1", NOW.minusSeconds(9000)), NOW);

		assertThat(repo.unenumeratedDuePosts(brandId, NOW.minusSeconds(86400), NOW.minusSeconds(2000))
				.stream().map(TaggedPostRepository.TrackedPost::shortCode))
				.containsExactly("H_IN", "D_OLD");   // 미보강 우선 동순위 → taken_at DESC
		// null floor = 기존 동작(전부)
		assertThat(repo.unenumeratedDuePosts(brandId, NOW.minusSeconds(86400), null))
				.hasSize(3);
	}

	@Test
	void touchFrozenHashtag은_floor_밖_해시태그_행만_동결_touch한다() {
		repo.upsertHashtag(brandId, post("H_IN", "poster1", NOW.minusSeconds(1000)), NOW);
		repo.upsertHashtag(brandId, post("H_OUT", "poster1", NOW.minusSeconds(5000)), NOW);
		repo.upsertDirect(brandId, post("D_OLD", "poster1", NOW.minusSeconds(9000)), NOW);
		repo.insert(brandId, post("TAGOLD", "poster1", NOW.minusSeconds(9000)));   // tagged-only — 대상 밖

		repo.touchFrozenHashtag(brandId, NOW.minusSeconds(2000), NOW);

		assertThat(db.queryForObject("SELECT last_crawled_at FROM brand_tagged_post"
				+ " WHERE brand_id = ? AND short_code = 'H_OUT'", java.sql.Timestamp.class, brandId)
				.toInstant()).isEqualTo(NOW);
		for (String untouched : List.of("H_IN", "D_OLD", "TAGOLD")) {
			assertThat(db.queryForObject("SELECT last_crawled_at IS NULL FROM brand_tagged_post"
					+ " WHERE brand_id = ? AND short_code = ?", Boolean.class, brandId, untouched))
					.as(untouched).isTrue();
		}
		// 되감기 금지 — 더 이른 at으로 재호출해도 유지
		repo.touchFrozenHashtag(brandId, NOW.minusSeconds(2000), NOW.minusSeconds(100));
		assertThat(db.queryForObject("SELECT last_crawled_at FROM brand_tagged_post"
				+ " WHERE brand_id = ? AND short_code = 'H_OUT'", java.sql.Timestamp.class, brandId)
				.toInstant()).isEqualTo(NOW);
	}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :monitoring:test --tests "com.celfit.monitoring.store.TaggedPostHashtagSourceTest"`
Expected: FAIL — 새 메서드 3개 컴파일 에러(존재하지 않음).

- [ ] **Step 3: 구현** — `TaggedPostRepository`에 추가. `nthNewestHashtagTakenAt`은 기존 `nthNewestTagTakenAt`(52행) 바로 아래에, 나머지는 `unenumeratedDuePosts`(276행) 주변에:

```java
	/**
	 * 해시태그 감시 세트의 바닥(2026-09-02 감시 세트 2,000 설계 §1) — hashtag 성분 행 중 게시일
	 * n번째 최신의 taken_at. 행이 n개 미만이면(세트 미포화) empty — 이때는 바닥이 없다.
	 * {@link #nthNewestTagTakenAt}의 hashtag판(같은 OFFSET 관용구).
	 */
	public Optional<Instant> nthNewestHashtagTakenAt(long brandId, int n) {
		if (n <= 0) {
			return Optional.empty();
		}
		return db.query("""
				SELECT taken_at FROM brand_tagged_post
				WHERE brand_id = ? AND hashtag_detected_at IS NOT NULL
				ORDER BY taken_at DESC
				OFFSET ? LIMIT 1""",
				(rs, i) -> rs.getTimestamp("taken_at").toInstant(), brandId, n - 1)
				.stream().findFirst();
	}
```

기존 `unenumeratedDuePosts(long, Instant)` 본문을 3-인자 위임으로 바꾸고 3-인자판 신설:

```java
	public List<TrackedPost> unenumeratedDuePosts(long brandId, Instant minTakenAt) {
		return unenumeratedDuePosts(brandId, minTakenAt, null);
	}

	/**
	 * floor판(2026-09-02 감시 세트 2,000 설계 §3) — hashtag 성분 행을 감시 세트 바닥
	 * ({@code hashtagFloor}, {@link #nthNewestHashtagTakenAt}) 이상으로 한정한다. direct 행은
	 * 바닥과 무관하게 항상 모수다(직접 등록은 상한 없음 — 설계 §1). floor가 null이면(세트 미포화)
	 * 기존과 동일하게 전부 돌려준다. <b>세트 밖 행을 여기서 걸러야 하는 이유</b>: 매일 티어(0~14일)
	 * 는 last_crawled_at과 무관하게 매일 due라, 동결 touch만으로는 다음 스윕 모수에서 안 빠진다.
	 */
	public List<TrackedPost> unenumeratedDuePosts(long brandId, Instant minTakenAt, Instant hashtagFloor) {
		Timestamp floor = hashtagFloor == null ? null : Timestamp.from(hashtagFloor);
		return db.query("""
				SELECT short_code, taken_at, last_crawled_at FROM brand_tagged_post
				WHERE brand_id = ?
				  AND (direct_registered_at IS NOT NULL
				       OR (hashtag_detected_at IS NOT NULL AND (?::timestamptz IS NULL OR taken_at >= ?)))
				  AND taken_at >= ?
				ORDER BY (enriched_at IS NULL) DESC, taken_at DESC""",
				(rs, i) -> {
					Timestamp last = rs.getTimestamp("last_crawled_at");
					return new TrackedPost(rs.getString("short_code"),
							rs.getTimestamp("taken_at").toInstant(),
							last == null ? null : last.toInstant());
				}, brandId, floor, floor, Timestamp.from(minTakenAt));
	}

	/**
	 * 감시 세트 밖 해시태그 행 동결 touch(2026-09-02 설계 §3) — tagged의
	 * {@link #touchCrawledDepth}(커버 간주)와 동형: 실수집 없이 last_crawled_at만 갱신해
	 * "이 깊이는 정책상 커버됨(동결 서빙)"으로 기록한다. direct 성분 행은 제외(항상 실수집 대상).
	 * tagged 겹침 행은 포함한다 — tagged의 깊이 touch·trackedPosts는 hashtag 성분 행을 아예
	 * 안 보므로(각 필터의 {@code hashtag_detected_at IS NULL}) 이 행들의 동결은 여기 소관이다.
	 * 되감기 방지 가드로 같은 날 중복 호출·개별 touch와의 경합에도 안전하다.
	 */
	public void touchFrozenHashtag(long brandId, Instant floorTakenAt, Instant at) {
		db.update("""
				UPDATE brand_tagged_post SET last_crawled_at = ?
				WHERE brand_id = ? AND hashtag_detected_at IS NOT NULL AND direct_registered_at IS NULL
				  AND taken_at < ?
				  AND (last_crawled_at IS NULL OR last_crawled_at < ?)""",
				Timestamp.from(at), brandId, Timestamp.from(floorTakenAt), Timestamp.from(at));
	}
```

(`java.sql.Timestamp`·`Optional`은 이미 임포트돼 있다 — 없으면 추가.)

- [ ] **Step 4: 통과 확인**

Run: `./gradlew :monitoring:test --tests "com.celfit.monitoring.store.TaggedPostHashtagSourceTest"`
Expected: PASS (기존 테스트 포함 전부).

- [ ] **Step 5: 커밋**

```bash
git add monitoring/src/main/java/com/celfit/monitoring/store/TaggedPostRepository.java monitoring/src/test/java/com/celfit/monitoring/store/TaggedPostHashtagSourceTest.java
git commit -m "feat(monitoring): 해시태그 감시 세트 경계 헬퍼 3종 (floor 조회·floor 한정 due·동결 touch)"
```

---

### Task 2: BrandDirectCollectService — 2단계를 감시 세트로 한정 + 동결 touch + 상한 300→2000

**Files:**
- Modify: `monitoring/src/main/java/com/celfit/monitoring/service/BrandDirectCollectService.java`
- Modify: `monitoring/src/main/resources/application.yml` (`unenumerated-sweep-limit`)
- Test: `monitoring/src/test/java/com/celfit/monitoring/service/BrandDirectCollectServiceTest.java`

**Interfaces:**
- Consumes: Task 1의 `nthNewestHashtagTakenAt` / `unenumeratedDuePosts(brandId, minTakenAt, hashtagFloor)` / `touchFrozenHashtag`.
- Produces: `BrandDirectCollectService` 생성자에 마지막 파라미터 `int monitoringSetSize` 추가 — `new BrandDirectCollectService(hiker, callContext, writer, taggedPosts, collect, sweepLimit, monitoringSetSize)`. Task 3과 같은 설정 키 `monitoring.brand.hashtag.post-limit`(기본 2000)를 읽는다.

- [ ] **Step 1: 실패하는 테스트 작성** — `BrandDirectCollectServiceTest`의 `InMemoryTagged` 스텁에 캡처 필드·오버라이드 추가:

```java
		Instant nthNewestHashtag;                       // 스텁 floor 응답(null = 세트 미포화)
		Instant capturedFloor;                          // unenumeratedDuePosts에 전달된 floor 캡처
		final List<Instant> frozenTouches = new ArrayList<>();  // touchFrozenHashtag(floor) 호출 캡처

		@Override
		public java.util.Optional<Instant> nthNewestHashtagTakenAt(long brandId, int n) {
			return java.util.Optional.ofNullable(nthNewestHashtag);
		}

		@Override
		public List<TrackedPost> unenumeratedDuePosts(long brandId, Instant minTakenAt, Instant hashtagFloor) {
			capturedFloor = hashtagFloor;
			return unenumeratedDuePosts(brandId, minTakenAt);   // 필터 자체는 Task 1 DB 테스트가 고정
		}

		@Override
		public void touchFrozenHashtag(long brandId, Instant floorTakenAt, Instant at) {
			frozenTouches.add(floorTakenAt);
		}
```

주의: 스텁의 기존 `unenumeratedDuePosts(long, Instant)` 오버라이드는 그대로 둔다(실물이 3-인자로 위임해도 스텁 2-인자가 모수 공급원). 테스트 2개 추가(기존 `serviceWithLimit` 헬퍼가 새 생성자 시그니처로 깨지므로 함께 수정 — `monitoringSetSize` 인자 추가, 기본 2000):

```java
	/** 감시 세트가 포화면(floor 존재) 2단계는 동결 touch 후 floor 한정 모수로 돈다(설계 §3). */
	@Test
	void 스윕2단계는_세트_바닥을_동결_touch하고_같은_바닥으로_모수를_자른다() {
		Instant floor = Instant.ofEpochSecond(NOW - 7L * 86400);
		tagged.nthNewestHashtag = floor;
		tagged.due.add(new TaggedPostRepository.TrackedPost("AAA", Instant.ofEpochSecond(RECENT), null));
		postResponses.put("AAA", singlePostJson("AAA", RECENT));

		serviceWithLimit(0).sweepUnenumerated(brand);

		assertThat(tagged.frozenTouches).containsExactly(floor);
		assertThat(tagged.capturedFloor).isEqualTo(floor);
	}

	/** 세트 미포화(floor 없음)면 동결 touch를 부르지 않고 기존 전체 모수 그대로다. */
	@Test
	void 세트_미포화면_동결_touch_없이_전체_모수로_돈다() {
		tagged.nthNewestHashtag = null;
		tagged.due.add(new TaggedPostRepository.TrackedPost("AAA", Instant.ofEpochSecond(RECENT), null));
		postResponses.put("AAA", singlePostJson("AAA", RECENT));

		serviceWithLimit(0).sweepUnenumerated(brand);

		assertThat(tagged.frozenTouches).isEmpty();
		assertThat(tagged.capturedFloor).isNull();
	}
```

(픽스처 헬퍼 이름 — due 목록 필드명·단건 응답 JSON 헬퍼 — 은 파일의 기존 2단계 테스트가 쓰는 것을 그대로 재사용한다. 위 코드의 `tagged.due`·`singlePostJson`은 그 기존 이름으로 치환할 것.)

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :monitoring:test --tests "com.celfit.monitoring.service.BrandDirectCollectServiceTest"`
Expected: FAIL — 생성자 인자 수·스텁 오버라이드 대상 없음 컴파일 에러.

- [ ] **Step 3: 구현** — `BrandDirectCollectService`:

생성자·필드(68행 부근) — 기본값 300→2000도 여기서 함께:

```java
	/** 해시태그 감시 세트 크기(2026-09-02 설계 §1) — 편입 쪽(BrandHashtagCollectService)과 같은 키. */
	private final int monitoringSetSize;

	public BrandDirectCollectService(HikerClient hiker, BrandCallContext callContext, BrandSnapshotWriter writer,
			TaggedPostRepository taggedPosts, BrandCollectService collect,
			@Value("${monitoring.brand.unenumerated-sweep-limit:2000}") int sweepLimit,
			@Value("${monitoring.brand.hashtag.post-limit:2000}") int monitoringSetSize) {
		...기존 대입...
		this.monitoringSetSize = monitoringSetSize;
	}
```

`doSweepUnenumerated`(132행) 모수 선정 앞에:

```java
	private void doSweepUnenumerated(BrandRow brand) {
		Instant now = Instant.now();
		// 감시 세트 바닥(2026-09-02 설계 §3) — hashtag 행이 세트 크기 이상이면 바닥이 생기고,
		// 바닥 밖 행은 ①동결 touch(커버 간주 — 대시보드·정렬 정합) ②모수 제외(매일 티어는 touch로
		// 안 꺼진다 — repo 주석 참조) 두 겹으로 처리한다. 이 시점의 바닥은 "어제까지의 편입" 기준이다
		// (스윕 순서가 ①tagged ②여기 ③hashtag 편입이라) — 오늘 편입분이 바닥을 밀어올리는 효과는
		// 다음 날 스윕부터 반영되며, 하루 지연은 수용한다(설계 §3).
		Instant floor = monitoringSetSize <= 0 ? null
				: taggedPosts.nthNewestHashtagTakenAt(brand.id(), monitoringSetSize).orElse(null);
		if (floor != null) {
			taggedPosts.touchFrozenHashtag(brand.id(), floor, now);
		}
		List<TaggedPostRepository.TrackedPost> dueAll = taggedPosts
				.unenumeratedDuePosts(brand.id(), now.minus(BrandCrawlPolicy.TRACKED_MAX_AGE), floor).stream()
				.filter(t -> BrandCrawlPolicy.due(t.takenAt(), t.lastCrawledAt(), now))
				.toList();
		...이하 기존 그대로(sweepLimit 컷·배치 보강)...
```

`application.yml`의 `unenumerated-sweep-limit: 300` → `2000`, 주석 교체:

```yaml
    unenumerated-sweep-limit: 2000  # 스윕당 2단계 재수집(direct∪hashtag) 상한 — 감시 세트 크기(hashtag.post-limit)와
                                    # 정합값(2026-09-02 감시 세트 설계 §3: 모수가 세트로 한정되므로 사실상 안전 밸브).
                                    # 0 이하 = 무제한. 구 300(2026-08-27 점진 상환)은 캠페인 브랜드의 매일 티어 due가
                                    # 상한을 매일 초과해 꼬리가 구조적으로 밀렸다(09-02 미처리 1,828건 진단).
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew :monitoring:test --tests "com.celfit.monitoring.service.BrandDirectCollectServiceTest"`
Expected: PASS 전부(기존 2단계 테스트는 스텁 floor 기본 null → 동작 불변이라 무수정 통과해야 한다. 깨지면 스텁 배선을 의심).

- [ ] **Step 5: 커밋**

```bash
git add monitoring/src/main/java/com/celfit/monitoring/service/BrandDirectCollectService.java monitoring/src/main/resources/application.yml monitoring/src/test/java/com/celfit/monitoring/service/BrandDirectCollectServiceTest.java
git commit -m "feat(monitoring): 2단계 재수집을 해시태그 감시 세트로 한정, 세트 밖 동결 touch, 상한 300→2000"
```

---

### Task 3: BrandHashtagCollectService — 하드스톱 폐기, 롤링 편입

**Files:**
- Modify: `monitoring/src/main/java/com/celfit/monitoring/service/BrandHashtagCollectService.java`
- Modify: `monitoring/src/main/java/com/celfit/monitoring/config/BrandHashtagConfig.java` (기본값)
- Modify: `monitoring/src/main/resources/application.yml` (`hashtag.max-pages`, 주석)
- Test: `monitoring/src/test/java/com/celfit/monitoring/service/BrandHashtagCollectServiceTest.java`

**Interfaces:**
- Consumes: Task 1의 `nthNewestHashtagTakenAt`.
- Produces: `sweepTag`/`SweepState` 내부 변경만 — 공개 시그니처 불변(`sweep(BrandRow)`). Task 4가 `sweepTag`의 `deep` 파라미터를 쓴다(이 태스크에서 파라미터만 뚫고 항상 false로 배선).

**의미 변경 요약** (기존 테스트 수정 근거): 구 규칙 "hashtag 행 postLimit 도달 → 브랜드 열거 전체 중단(신규 드랍)"을 폐기한다. 새 규칙 — ① 예산 = `postLimit − 기존 hashtag 행 수`(백필 용량), ② 예산 소진 후에도 **세트 바닥(floor)보다 최신 게시물은 편입**(롤링 — 바닥 행은 Task 2가 동결), ③ 예산 0 + 페이지 전체가 바닥 이하 + 편입 0이면 그 태그 열거 중단(낭비 가드).

- [ ] **Step 1: 실패하는 테스트 작성** — `BrandHashtagCollectServiceTest`:

먼저 기존 상한 테스트 파악: `grep -n "상한\|budget\|postLimit" monitoring/src/test/java/com/celfit/monitoring/service/BrandHashtagCollectServiceTest.java`. "상한 도달 시 잔여 태그 열거 중단"류 테스트는 새 의미(중단이 아니라 바닥 기반 선별)로 아래처럼 고쳐 쓴다. `InMemoryTagged` 스텁에 오버라이드 추가:

```java
		Instant nthNewestHashtag;   // floor 스텁 — null이면 세트 미포화

		@Override
		public java.util.Optional<Instant> nthNewestHashtagTakenAt(long brandId, int n) {
			return java.util.Optional.ofNullable(nthNewestHashtag);
		}
```

새 테스트 3개(서비스 생성 헬퍼는 파일 기존 것을 쓰되 postLimit 인자를 조절):

```java
	/** 롤링 편입(설계 §2) — 예산 0이어도 세트 바닥보다 최신 게시물은 편입된다(구 하드스톱 폐기). */
	@Test
	void 예산_소진_후에도_바닥보다_최신_게시물은_편입된다() {
		tagged.hashtag.add("OLD1");   // 기존 hashtag 행 1개 → postLimit 1이면 예산 0
		tagged.known.add("OLD1");
		tagged.nthNewestHashtag = Instant.ofEpochSecond(RECENT - 86400);   // 바닥 = RECENT-1일
		tags.tags = List.of("t1");
		pagesByTag.put("t1", List.of(pageJson(List.of(
				postJson("NEWEST", "poster1", RECENT),                    // 바닥보다 최신 → 편입
				postJson("DEEPER", "poster2", RECENT - 3 * 86400)), null)));  // 바닥 이하 → 스킵

		service(1).sweep(brand);   // postLimit 1

		assertThat(tagged.upsertedHashtag).containsExactly("NEWEST");
	}

	/** 낭비 가드 — 예산 0 + 페이지 전체가 바닥 이하 + 편입 0이면 다음 페이지로 안 내려간다. */
	@Test
	void 예산_소진_후_바닥_이하만_남은_페이지에서_열거를_끊는다() {
		tagged.hashtag.add("OLD1");
		tagged.known.add("OLD1");
		tagged.nthNewestHashtag = Instant.ofEpochSecond(RECENT);
		tags.tags = List.of("t1");
		pagesByTag.put("t1", List.of(
				pageJson(List.of(postJson("DEEP1", "poster1", RECENT - 5 * 86400)), "cur2"),
				pageJson(List.of(postJson("DEEP2", "poster2", RECENT - 6 * 86400)), null)));

		service(1).sweep(brand);

		assertThat(tagged.upsertedHashtag).isEmpty();
		assertThat(calls).hasSize(1);   // 2페이지째 콜이 없어야 한다
	}

	/** 백필 예산은 태그 간 공유 유지 — 태그1이 예산을 다 쓰면 태그2의 옛 게시물은 편입 안 된다. */
	@Test
	void 백필_예산은_태그_간_공유다() {
		tags.tags = List.of("t1", "t2");
		tagged.nthNewestHashtag = null;   // 세트 미포화 — 롤링 편입 경로 없음, 예산만 적용
		pagesByTag.put("t1", List.of(pageJson(List.of(
				postJson("A1", "poster1", RECENT), postJson("A2", "poster2", RECENT)), null)));
		pagesByTag.put("t2", List.of(pageJson(List.of(
				postJson("B1", "poster3", RECENT)), null)));

		service(2).sweep(brand);   // postLimit 2 → t1이 소진

		assertThat(tagged.upsertedHashtag).containsExactly("A1", "A2");
	}
```

(`pageJson`/`postJson`/`calls`/`service(int postLimit)` — 파일의 기존 페이지 픽스처·생성 헬퍼 이름으로 치환. 없으면 기존 테스트가 페이지를 만드는 방식을 그대로 복제해 헬퍼로 추출한다.)

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :monitoring:test --tests "com.celfit.monitoring.service.BrandHashtagCollectServiceTest"`
Expected: FAIL — 새 테스트 3개 실패(구 하드스톱 동작), 스텁 오버라이드 컴파일 에러 가능.

- [ ] **Step 3: 구현** — `BrandHashtagCollectService`:

`SweepState`에 floor 추가(86행 부근):

```java
		/** 감시 세트 바닥(2026-09-02 설계 §2) — null이면 세트 미포화(예산으로만 판정). 스윕 시작
		 * 시점 스냅샷이라 이번 실행의 편입이 바닥을 밀어올리는 효과는 다음 스윕부터다 — 그 사이의
		 * 초과 편입은 한 스윕치 유입으로 유계라 수용한다. */
		final Instant floor;

		SweepState(Set<String> known, Set<String> hashtagKnown, int budget, Instant floor) { ... }

		/** 롤링 편입 판정 — 바닥이 있고 그보다 최신이면 예산 없이도 편입(설계 §2). */
		boolean admitsByFloor(PostInfo p) {
			return floor != null && p.takenAt() != null
					&& Instant.ofEpochSecond(p.takenAt()).isAfter(floor);
		}
```

`doSweep`(109행): state 생성을 아래로 교체하고, **태그 루프의 `budget <= 0 → break`(121~124행)를 제거**(하드스톱 폐기 — 예산이 없어도 각 태그의 최신 유입은 편입해야 한다. 대신 태그별 낭비 가드가 페이지를 끊는다):

```java
		Set<String> hashtagKnown = taggedPosts.hashtagCodes(brand.id());
		int budget = postLimit <= 0 ? Integer.MAX_VALUE : Math.max(0, postLimit - hashtagKnown.size());
		Instant floor = postLimit <= 0 ? null
				: taggedPosts.nthNewestHashtagTakenAt(brand.id(), postLimit).orElse(null);
		SweepState state = new SweepState(new HashSet<>(taggedPosts.knownCodes(brand.id())),
				hashtagKnown, budget, floor);
```

`sweepTag`(155행) — 시그니처에 `boolean deep` 추가(이 태스크에선 호출부 `doSweep`이 `false` 전달), `brandNew` 선별(176~179행)을 스트림 `limit`에서 명시 루프로 교체하고 페이지 끝의 예산 차감(188행)을 제거(선별 시 즉시 차감으로 이동 — "페이지 단위 즉시 차감"보다 반 발 이른 지점이라 격리 예외 시 과편입 방지 방향은 동일):

```java
		List<PostInfo> overlap = fresh.stream().filter(p -> state.known.contains(p.shortCode())).toList();
		List<PostInfo> brandNew = new ArrayList<>();
		for (PostInfo p : fresh) {
			if (state.known.contains(p.shortCode())) {
				continue;
			}
			if (state.budget > 0) {
				state.budget--;          // 백필 용량 소모(선별 즉시 차감 — 구 페이지 말미 차감의 강화판)
				brandNew.add(p);
			} else if (state.admitsByFloor(p)) {
				brandNew.add(p);         // 롤링 편입 — 세트 바닥 위는 예산 없이 편입(하드스톱 폐기)
			}
		}
```

`collectPage` 호출 뒤, 조기 종료 판정(197행) 앞에 낭비 가드:

```java
		// 낭비 가드(설계 §2) — 예산 0에서 이 페이지가 아무것도 편입 못 했고 fresh 전원이 바닥
		// 이하면, 더 내려가도 편입 가능성이 없다(비단조 스트림이라 "전부"일 때만 끊는다).
		if (state.budget <= 0 && brandNew.isEmpty() && !fresh.isEmpty()
				&& fresh.stream().noneMatch(state::admitsByFloor)) {
			break;
		}
```

조기 종료(197~199행)는 `if (!deep && alreadyHashtag.stream()...)` 로 deep 게이트만 추가. 클래스 javadoc의 "편입 상한" 문단(44~49행)을 새 의미(롤링 세트·백필 예산·낭비 가드)로 다시 쓴다. `doSweep`의 상한 로그(122행)는 제거하고, 마지막 요약 로그는 유지.

설정 기본값: `BrandHashtagConfig`의 `post-limit:1000` → `post-limit:2000`, `application.yml`:

```yaml
    hashtag:
      max-pages: 100             # 태그당 recent 열거 안전 밸브(2026-09-02 감시 세트 설계 §2 — 구 4).
                                 # 정상 종료는 예산 소진·dedup·낭비 가드·스트림 소진이 먼저 온다.
                                 # 세트 2,000 ≈ 85페이지에 여유를 둔 값.
      post-limit: 2000           # 해시태그 감시 세트 크기(2026-09-02 설계 §1) — 구 "편입 하드스톱"이
                                 # 아니라 롤링 세트다: 신규는 항상 편입, 게시일 최신 2,000 밖은 동결.
                                 # 2단계 재수집(BrandDirectCollectService)이 같은 키를 읽는다. 0 이하 = 무제한.
```

(주의: `post-limit`이 yml에 명시돼 있지 않았다면 — 현재는 코드 기본값 1000만 존재 — 위처럼 **명시로 추가**한다.)

- [ ] **Step 4: 통과 확인** — 기존 테스트 중 구 하드스톱 의미를 고정하던 것들이 깨질 수 있다. 깨진 테스트는 새 의미로 다시 쓰되(예: "상한 도달 → 잔여 태그 중단" → "예산 소진 → 옛 게시물만 스킵, 태그 루프는 계속"), **동작 회귀가 아니라 의미 변경임을 각 테스트 javadoc에 설계 링크로 남긴다.**

Run: `./gradlew :monitoring:test --tests "com.celfit.monitoring.service.BrandHashtagCollectServiceTest"`
Expected: PASS 전부.

- [ ] **Step 5: 커밋**

```bash
git add monitoring/src/main/java/com/celfit/monitoring/service/BrandHashtagCollectService.java monitoring/src/main/java/com/celfit/monitoring/config/BrandHashtagConfig.java monitoring/src/main/resources/application.yml monitoring/src/test/java/com/celfit/monitoring/service/BrandHashtagCollectServiceTest.java
git commit -m "feat(monitoring): 해시태그 편입 하드스톱 폐기 — 최신 2,000 롤링 감시 세트로 전환"
```

---

### Task 4: 딥 재백필 1회 경로 (deep resweep)

**Files:**
- Modify: `monitoring/src/main/java/com/celfit/monitoring/service/BrandHashtagCollectService.java` (공개 진입점)
- Create: `monitoring/src/main/java/com/celfit/monitoring/service/HashtagDeepResweepStartupRunner.java`
- Modify: `monitoring/src/main/resources/application.yml` (플래그)
- Test: `monitoring/src/test/java/com/celfit/monitoring/service/BrandHashtagCollectServiceTest.java`

**Interfaces:**
- Consumes: Task 3의 `sweepTag(brand, tag, cutoff, now, state, deep)`.
- Produces: `BrandHashtagCollectService.deepResweep(BrandRow brand)` — `sweep`과 동일 골격이되 dedup 조기 종료를 무시(deep=true). 러너 전용.

- [ ] **Step 1: 실패하는 테스트 작성**:

```java
	/** 딥 재백필(설계 §2) — dedup 조기 종료를 무시하고 예산까지 내려가 하드스톱 기간 유실분을 줍는다. */
	@Test
	void 딥_재백필은_기존_행을_만나도_다음_페이지로_내려간다() {
		tagged.hashtag.add("KNOWN1");
		tagged.known.add("KNOWN1");
		tagged.nthNewestHashtag = null;
		tags.tags = List.of("t1");
		pagesByTag.put("t1", List.of(
				pageJson(List.of(postJson("KNOWN1", "poster1", RECENT)), "cur2"),  // 일반 sweep은 여기서 종료
				pageJson(List.of(postJson("LOST1", "poster2", RECENT - 86400)), null)));

		service(2000).deepResweep(brand);

		assertThat(tagged.upsertedHashtag).containsExactly("LOST1");
		assertThat(calls).hasSize(2);
	}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :monitoring:test --tests "com.celfit.monitoring.service.BrandHashtagCollectServiceTest"`
Expected: FAIL — `deepResweep` 없음.

- [ ] **Step 3: 구현**:

`BrandHashtagCollectService` — `doSweep(BrandRow)`을 `doSweep(BrandRow, boolean deep)`으로 바꾸고(`sweep`은 false 전달) 진입점 추가:

```java
	/**
	 * 딥 재백필 1회 경로(2026-09-02 설계 §2) — 구 하드스톱(post-limit 1000) 기간에 편입이 막혀
	 * 버려진 게시물은 dedup 조기 종료(페이지에 기존 행이 보이면 중단) 탓에 증분 스윕으로는 영영
	 * 회수되지 않는다. 이 경로는 조기 종료만 무시하고 나머지(예산·낭비 가드·maxPages·수집 창)는
	 * 일반 스윕과 동일하다. recent 스트림이 옛 게시물을 어디까지 돌려주는지는 보장이 없어 회수는
	 * best-effort다. {@link HashtagDeepResweepStartupRunner} 전용 — 상시 경로에 쓰지 말 것
	 * (매 스윕 딥으로 돌면 태그당 페이지 콜이 상시 ~수십 배가 된다).
	 */
	public void deepResweep(BrandRow brand) {
		callContext.runScoped(brand.id(), () -> doSweep(brand, true));
	}
```

새 러너 — `UnenrichedBackfillStartupRunner` 동형 골격:

```java
package com.celfit.monitoring.service;

import com.celfit.monitoring.store.BrandRepository;
import com.celfit.monitoring.store.BrandRow;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 해시태그 딥 재백필 1회 러너(2026-09-02 감시 세트 2,000 설계 §2) — 구 편입 하드스톱(1,000)
 * 기간에 버려진 게시물을 상한 상향 직후 한 번 회수한다. {@link UnenrichedBackfillStartupRunner}와
 * 동형 골격(기동 완료 후 데몬 스레드, 브랜드 단위 격리)이되 <b>기본 꺼짐</b>이다 — dedup을 무시하는
 * 딥 열거는 no-op이 아니라 매 재기동마다 태그당 수십 페이지 콜을 낸다. 운영 절차: 상향 배포 시
 * env로 {@code MONITORING_BRAND_HASHTAG_DEEP_RESWEEP_ON_STARTUP=true} 1회 주입 → 완료 로그 확인
 * 후 다음 배포에서 제거.
 */
@Component
public class HashtagDeepResweepStartupRunner {

	private static final Logger log = LoggerFactory.getLogger(HashtagDeepResweepStartupRunner.class);

	private final BrandRepository brands;
	private final BrandHashtagCollectService hashtagCollect;
	private final boolean enabled;

	public HashtagDeepResweepStartupRunner(BrandRepository brands,
			BrandHashtagCollectService hashtagCollect,
			@Value("${monitoring.brand.hashtag.deep-resweep-on-startup:false}") boolean enabled) {
		this.brands = brands;
		this.hashtagCollect = hashtagCollect;
		this.enabled = enabled;
	}

	@EventListener(ApplicationReadyEvent.class)
	public void onApplicationReady() {
		if (!enabled) {
			return;
		}
		Thread t = new Thread(this::runSafely, "brand-hashtag-deep-resweep-startup");
		t.setDaemon(true);
		t.start();
	}

	private void runSafely() {
		List<BrandRow> active = brands.findActive();
		int failures = 0;
		for (BrandRow b : active) {
			try {
				hashtagCollect.deepResweep(b);
			} catch (RuntimeException e) {
				failures++;
				log.warn("해시태그 딥 재백필 실패(격리) — {}: {}", b.username(), e.toString());
			}
		}
		log.info("해시태그 딥 재백필 완료 — 브랜드 {}건 중 실패 {}건", active.size(), failures);
	}
}
```

`application.yml`의 hashtag 블록에:

```yaml
      deep-resweep-on-startup: false  # 딥 재백필 1회 킬 스위치(2026-09-02 설계 §2) — 상한 상향 배포
                                      # 시에만 env로 true 1회 주입(러너 javadoc의 운영 절차 참조)
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew :monitoring:test --tests "com.celfit.monitoring.service.BrandHashtagCollectServiceTest"`
Expected: PASS.

- [ ] **Step 5: 커밋**

```bash
git add monitoring/src/main/java/com/celfit/monitoring/service/BrandHashtagCollectService.java monitoring/src/main/java/com/celfit/monitoring/service/HashtagDeepResweepStartupRunner.java monitoring/src/main/resources/application.yml monitoring/src/test/java/com/celfit/monitoring/service/BrandHashtagCollectServiceTest.java
git commit -m "feat(monitoring): 해시태그 딥 재백필 1회 러너 — 하드스톱 기간 유실분 회수(기본 꺼짐)"
```

---

### Task 5: was 노출 상한 폐지

**Files:**
- Modify: `was/src/main/java/com/celfit/was/v1/brandmonitoring/BrandCollectionCap.java`
- Test: `was/src/test/java/com/celfit/was/v1/brandmonitoring/BrandCollectionCapTest.java`

**Interfaces:**
- Produces: `BrandCollectionCap.apply(windowed)` — 시그니처·`Capped` record 불변, 단 컷 없이 전량 반환·`capped()`는 항상 false. `V1BrandPostsController`·`/influencers` 호출부는 무수정(자동으로 전량 서빙). `BrandAccountResponse.collectionCapped`(백필 커버리지)는 **별개 필드 — 손대지 않는다.**

- [ ] **Step 1: 테스트 수정** — `BrandCollectionCapTest`에서 "2,000 초과분이 잘린다"류 테스트를 "전량 반환·capped=false"로 다시 쓴다:

```java
	@Test
	void 상한_없이_전량을_반환한다() {   // 2026-09-02 노출 상한 폐지 설계 §4 — 구 2,000 컷 테스트 대체
		List<BrandPostAssembler.PostRef> refs = manyRefs(2500);   // 파일 기존 픽스처 헬퍼 재사용

		BrandCollectionCap.Capped result = BrandCollectionCap.apply(refs);

		assertThat(result.refs()).hasSize(2500);
		assertThat(result.capped()).isFalse();
	}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :was:test --tests "com.celfit.was.v1.brandmonitoring.BrandCollectionCapTest"`
Expected: FAIL — 현행은 2,000에서 자른다.

- [ ] **Step 3: 구현** — `apply`를 다음으로 교체하고 클래스 javadoc에 폐지 경위(2026-09-02 설계 §4, "수집한 만큼 보여준다" 원칙은 수집 쪽 롤링 세트로 이동)를 남긴다. `POST_LIMIT`·`UPLOADED_DESC` 상수는 제거(참조 없어짐 — Error Prone unused 방지), 단 `Capped.capped`는 FE 계약(`meta.collectionCapped`) 호환으로 유지:

```java
	static Capped apply(List<BrandPostAssembler.PostRef> windowed) {
		// 노출 컷 폐지(2026-09-02 감시 세트 설계 §4) — 신선도 통제가 수집 쪽 롤링 세트로 옮겨가
		// 서빙은 창 안 전량이다. capped 필드는 FE 계약(meta.collectionCapped) 호환으로 남긴다.
		return new Capped(windowed, false);
	}
```

- [ ] **Step 4: 통과 확인** — 컨트롤러 테스트까지:

Run: `./gradlew :was:test --tests "com.celfit.was.v1.brandmonitoring.*"`
Expected: PASS. (V1BrandPostsControllerTest 등에 2,000 컷 전제 테스트가 있으면 같은 방식으로 의미 갱신.)

- [ ] **Step 5: 커밋**

```bash
git add was/src/main/java/com/celfit/was/v1/brandmonitoring/BrandCollectionCap.java was/src/test/java/com/celfit/was/v1/brandmonitoring/
git commit -m "feat(was): 브랜드 게시물 노출 상한(2,000) 폐지 — 창 안 전량 서빙"
```

---

### Task 6: Grafana 대시보드 — 미처리 출처 분리

**Files:**
- Modify: `deploy/grafana/provisioning/dashboards/json-flow/hypenow-flow-brand.json`

이 태스크는 테스트가 없다(대시보드 JSON) — 검증은 JSON 파싱 + SQL 문법 확인으로 한다.

- [ ] **Step 1: "미처리 브랜드별" 패널의 rawSql 교체** — 파이썬으로 해당 패널을 찾아(`title == '미처리 브랜드별'`) `targets[0].rawSql`을 아래로 교체한다(한 줄로 접어서). 핵심 변경: `pure_tagged` 성분 분리, 미처리 2열 분할, 판독 3분기:

```sql
WITH posts AS (SELECT t.brand_id,
  (t.tag_detected_at IS NOT NULL AND t.hashtag_detected_at IS NULL AND t.direct_registered_at IS NULL) AS pure_tagged,
  coalesce((t.last_crawled_at AT TIME ZONE 'Asia/Seoul')::date = (now() AT TIME ZONE 'Asia/Seoul')::date, false) AS done_today,
  (t.taken_at >= now() - interval '180 days' AND (t.last_crawled_at IS NULL OR t.taken_at >= now() - interval '14 days' OR (t.taken_at >= now() - interval '30 days' AND t.last_crawled_at <= now() - interval '3 days') OR (t.taken_at >= now() - interval '90 days' AND t.last_crawled_at <= now() - interval '7 days') OR t.last_crawled_at <= now() - interval '30 days')) AS due_now
  FROM brand_tagged_post t WHERE t.taken_at IS NOT NULL),
agg AS (SELECT brand_id,
  count(*) FILTER (WHERE NOT done_today AND due_now AND pure_tagged) AS pending_tagged,
  count(*) FILTER (WHERE NOT done_today AND due_now AND NOT pure_tagged) AS pending_other,
  count(*) FILTER (WHERE done_today) AS done
  FROM posts GROUP BY brand_id)
SELECT b.username AS "브랜드", a.pending_tagged AS "미처리(태그)", a.pending_other AS "미처리(해시·직접)",
  a.done AS "오늘 처리",
  CASE WHEN b.last_swept_on = (now() AT TIME ZONE 'Asia/Seoul')::date THEN '돌았으나 미달' ELSE '안 돌음' END AS "상태",
  b.sweep_completed_at AS "마지막 성공",
  CASE WHEN b.last_swept_on IS DISTINCT FROM (now() AT TIME ZONE 'Asia/Seoul')::date
         THEN '브랜드 수집 실패·미실행 — 새벽 2~3시면 순서 대기일 수 있음'
       WHEN a.pending_tagged > 0
         THEN '태그 피드 훑기가 중간에 끊김 — 다음 날 자동 재시도, 반복되면 로그 확인'
       ELSE '2단계 단건 재수집 잔여 — 로그 "2단계 단건 수집 상한" 확인, 이틀 연속이면 감시 세트 동결 touch 누락 의심' END AS "판독"
FROM agg a JOIN brand_account b ON b.id = a.brand_id AND b.closed_at IS NULL
WHERE a.pending_tagged + a.pending_other > 0
ORDER BY a.pending_tagged + a.pending_other DESC, b.username
```

패널 `description`도 갱신: 기존 문구 유지하되 끝에 한 단락 추가 — "미처리는 출처 2열로 갈린다: **미처리(태그)** = 순수 태그 행(피드 열거·깊이 touch 소관 — 기존 판독 그대로), **미처리(해시·직접)** = 2단계 단건 재수집 소관(2026-09-02 감시 세트 설계: 세트 안은 상한 2,000으로 매일 갱신, 세트 밖은 동결 touch로 due에서 빠지므로 이 열이 지속되면 상한 컷 로그나 동결 touch 누락을 본다)."

- [ ] **Step 2: "오늘 게시물 갱신 — 티어별" 패널 description 한 줄 추가** — "미처리 > 0은 진짜 이상이다" 문장 뒤에: "(2026-09-02~ 해시태그·직접 행 포함 — 출처별 소관은 아래 '미처리 브랜드별'의 2열이 가른다)".

- [ ] **Step 3: 검증** — JSON 파싱과 SQL 형태 확인:

```bash
python3 -c "
import json
d=json.load(open('deploy/grafana/provisioning/dashboards/json-flow/hypenow-flow-brand.json'))
def walk(ps):
    for p in ps:
        yield p
        yield from walk(p.get('panels',[]))
for p in walk(d['panels']):
    if p.get('title')=='미처리 브랜드별':
        sql=p['targets'][0]['rawSql']
        assert '미처리(태그)' in sql and '미처리(해시·직접)' in sql and 'pure_tagged' in sql
        print('OK')
"
```

Expected: `OK`.

- [ ] **Step 4: 커밋**

```bash
git add deploy/grafana/provisioning/dashboards/json-flow/hypenow-flow-brand.json
git commit -m "fix(grafana): 미처리 브랜드별 출처 2열 분리 — 해시태그·직접 행의 태그 피드 오진 제거"
```

---

### Task 7: 문서 갱신·마무리 검증

**Files:**
- Modify: `DECISIONS.md` (맨 위에 결정 추가)
- Modify: `docs/superpowers/specs/archive/2026-09-02-hashtag-monitoring-set-2000-design.md` (상태 헤더 → ✅ 구현됨)
- Move: `docs/superpowers/plans/2026-09-02-hashtag-monitoring-set-2000.md` → `docs/superpowers/plans/archive/`

- [ ] **Step 1: DECISIONS.md 맨 위에 결정 추가** (기존 항목 서식 그대로):

내용 요지 — "2026-09-02 해시태그 감시 세트 2,000 롤링 전환: 편입 하드스톱(1,000, 도달 시 신규 드랍) 폐기 → 게시일 최신 2,000 롤링 세트(세트 밖은 동결 touch·저장 유지). 2단계 재수집 상한 300→2,000(모수를 세트로 한정). was 노출 컷 2,000 폐지. 근거: 09-02 미처리 1,828건 진단(캠페인 브랜드 일일 due가 상한을 매일 초과 + celimax 2종 편입 상한 포화). 비용: 캠페인 피크 브랜드당 월 $20~40 승인. 스펙: docs/superpowers/specs/archive/2026-09-02-hashtag-monitoring-set-2000-design.md".

- [ ] **Step 2: 스펙 상태 헤더 갱신** — `> 상태: 🟢 활성 …` → `> 상태: ✅ 구현됨 (2026-09-02)`.

- [ ] **Step 3: 모듈 전체 테스트**

Run: `./gradlew :monitoring:test :was:test`
Expected: PASS 전부. 실패 시 해당 태스크로 돌아가 수정(원인 모르면 systematic-debugging).

- [ ] **Step 4: plan 아카이브 + 커밋**

```bash
mkdir -p docs/superpowers/plans/archive
git mv docs/superpowers/plans/2026-09-02-hashtag-monitoring-set-2000.md docs/superpowers/plans/archive/
git add DECISIONS.md docs/superpowers/specs/archive/2026-09-02-hashtag-monitoring-set-2000-design.md
git commit -m "docs: 해시태그 감시 세트 2,000 전환 결정 기록·스펙 상태 갱신·plan 아카이브"
```

- [ ] **Step 5: PR 생성** — develop 대상, 본문에 스펙 링크·비용 승인 사실·운영 절차(딥 재백필 env 1회 주입) 명시:

```bash
gh pr create --base develop --title "feat(monitoring/was): 해시태그 감시 세트 2,000 롤링 전환 + 노출 상한 폐지" --body "$(cat <<'EOF'
## 요약
- 해시태그 편입 하드스톱(1,000) 폐기 → 게시일 최신 2,000 롤링 감시 세트(세트 밖 동결 touch·저장 유지)
- 2단계 단건 재수집: 모수를 세트로 한정 + 상한 300→2,000 (09-02 미처리 1,828건 진단 해소)
- was 노출 컷 2,000 폐지(창 안 전량 서빙), Grafana 미처리 출처 2열 분리
- 딥 재백필 1회 러너(기본 꺼짐) — 하드스톱 기간 유실분 회수

## 배포 운영 절차
상향 배포 시 monitoring에 `MONITORING_BRAND_HASHTAG_DEEP_RESWEEP_ON_STARTUP=true` 1회 주입, 완료 로그("해시태그 딥 재백필 완료") 확인 후 다음 배포에서 제거.

스펙: docs/superpowers/specs/archive/2026-09-02-hashtag-monitoring-set-2000-design.md (비용 월 $20~40/캠페인 브랜드 — 09-02 사용자 승인)

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

---

## Self-Review 결과 (작성 시 수행)

- 스펙 §1(상한 재정의)→Task 1·3, §2(편입·백필·딥 재열거)→Task 3·4, §3(재수집·동결 touch·상한)→Task 1·2, §4(노출)→Task 5, §5(대시보드)→Task 6, §6(비용)→PR 본문, §7 표의 변경 지점 전부 태스크에 매핑됨 — 갭 없음.
- 시그니처 정합: `nthNewestHashtagTakenAt(long, int)` / `unenumeratedDuePosts(long, Instant, Instant)` / `touchFrozenHashtag(long, Instant, Instant)` / `sweepTag(..., boolean deep)` / `deepResweep(BrandRow)` — Task 간 일치 확인.
- 테스트 픽스처 헬퍼(`pageJson` 등)는 파일별 기존 이름으로 치환하라는 지시를 명시(파일마다 실명이 달라 하드코딩하지 않음 — 구현자는 반드시 기존 테스트의 페이지 생성 코드를 먼저 읽을 것).
