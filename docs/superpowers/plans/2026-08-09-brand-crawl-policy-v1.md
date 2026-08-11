# 브랜드 크롤링 정책 v1(나이 기반 티어) 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 브랜드 태그 수집을 "매일 전량(90일 & 105개)"에서 게시물 나이 기반 티어 주기 + 등록 시 12개월 백필로 전환한다.

**Architecture:** 티어 판정은 신규 순수 함수 `BrandCrawlPolicy`(taken_at·last_crawled_at·now만, 저장 상태 없음)가 담당하고, 페이지 단위 태그 열거 특성에 맞춰 스윕마다 "오늘의 열거 깊이"로 번역한다(깊은 열거가 얕은 티어 자동 포함). 스키마 변경은 `brand_tagged_post.last_crawled_at` 컬럼 1개. 정본 스펙: [docs/superpowers/specs/2026-08-09-brand-crawl-policy-v1-design.md](../specs/2026-08-09-brand-crawl-policy-v1-design.md)

**Tech Stack:** Java 21 · Spring Boot 4.1 · JdbcTemplate · Flyway(monitoring 독립 버전 공간) · JUnit 5 + AssertJ · Testcontainers(PostgreSQL, 스토어 테스트만)

## Global Constraints

- 테스트는 모듈 단위: `./gradlew :monitoring:test` (전체 `./gradlew test`는 이 계획에서 돌리지 않는다)
- 이 머신의 도커는 Docker Desktop — `DOCKER_HOST` **미설정이 정답**(08-09 확인. CLAUDE.md의 colima 항목은 다른 머신용)
- 신규 Flyway 마이그레이션은 UTC 타임스탬프 채번(`V<YYYYMMDDHHMMSS>__…`), expand-contract — 이 계획은 nullable ADD COLUMN 1개뿐이라 가드 통과
- 주석·로그·커밋 메시지는 한국어, 커밋 prefix `feat(monitoring):`/`docs:`, 커밋 말미에 `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`
- 티어 상수(14/30/90/180일, 3/7/30일 주기)는 정책 상수로 코드에 둔다 — app_setting·yml 토글 금지(스펙 §3)
- 단건 게시물 콜 전면 금지 유지 — 이 계획의 어떤 태스크도 새 Hiker 엔드포인트를 추가하지 않는다
- 들여쓰기·주석 스타일은 기존 monitoring 코드 관용구(탭, 한국어 javadoc, 스펙 참조 표기)를 따른다

---

### Task 1: `BrandCrawlPolicy` — 티어 판정 순수 함수

**Files:**
- Create: `monitoring/src/main/java/com/celfit/monitoring/service/BrandCrawlPolicy.java`
- Test: `monitoring/src/test/java/com/celfit/monitoring/service/BrandCrawlPolicyTest.java`

**Interfaces:**
- Consumes: 없음 (JDK만)
- Produces:
  - `public static boolean due(Instant takenAt, Instant lastCrawledAt, Instant now)`
  - `public static final Duration DAILY_MAX_AGE` (14일) — Task 3이 스윕 최소 깊이로 사용
  - `public static final Duration TRACKED_MAX_AGE` (180일) — Task 3이 trackedPosts 조회 플로어로 사용

- [ ] **Step 1: 실패하는 테스트 작성**

```java
package com.celfit.monitoring.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * 크롤링 정책 v1(2026-08-09 스펙 §3) — 나이 티어 순수 함수 판정. 경계값(14/30/90/180일)과
 * 주기 경계(3/7/30일), null last_crawled_at 수렴, 자가 치유를 고정한다.
 */
class BrandCrawlPolicyTest {

	private static final Instant NOW = Instant.parse("2026-08-09T03:00:00Z");

	private static Instant daysAgo(long d) {
		return NOW.minus(Duration.ofDays(d));
	}

	@Test
	void 나이_14일_이하는_스윕마다_due() {
		assertThat(BrandCrawlPolicy.due(daysAgo(0), NOW, NOW)).isTrue();
		assertThat(BrandCrawlPolicy.due(daysAgo(14), daysAgo(0), NOW)).isTrue();
	}

	@Test
	void 나이_15_30일은_3일_경과_시_due() {
		assertThat(BrandCrawlPolicy.due(daysAgo(20), daysAgo(2), NOW)).isFalse();
		assertThat(BrandCrawlPolicy.due(daysAgo(20), daysAgo(3), NOW)).isTrue();
	}

	@Test
	void 나이_31_90일은_7일_경과_시_due() {
		assertThat(BrandCrawlPolicy.due(daysAgo(60), daysAgo(6), NOW)).isFalse();
		assertThat(BrandCrawlPolicy.due(daysAgo(60), daysAgo(7), NOW)).isTrue();
	}

	@Test
	void 나이_91_180일은_30일_경과_시_due() {
		assertThat(BrandCrawlPolicy.due(daysAgo(120), daysAgo(29), NOW)).isFalse();
		assertThat(BrandCrawlPolicy.due(daysAgo(120), daysAgo(30), NOW)).isTrue();
	}

	@Test
	void 나이_180일_초과는_영구_제외() {
		// null last_crawled_at이어도 제외 — 발견 시 스냅샷 1회로 종료(스펙 §3)
		assertThat(BrandCrawlPolicy.due(daysAgo(181), null, NOW)).isFalse();
		assertThat(BrandCrawlPolicy.due(daysAgo(365), daysAgo(300), NOW)).isFalse();
	}

	@Test
	void last_crawled_at_null은_추적_범위_안에서_무조건_due() {
		// 마이그레이션 직후 기존 행·미완 수집분의 안전 수렴 경로
		assertThat(BrandCrawlPolicy.due(daysAgo(5), null, NOW)).isTrue();
		assertThat(BrandCrawlPolicy.due(daysAgo(60), null, NOW)).isTrue();
		assertThat(BrandCrawlPolicy.due(daysAgo(180), null, NOW)).isTrue();
	}

	@Test
	void 스윕_공백은_자가_치유된다() {
		// 20일령 게시물, 마지막 크롤 5일 전(스윕이 이틀 빠짐) — 경과 5일 ≥ 주기 3일이라 due
		assertThat(BrandCrawlPolicy.due(daysAgo(20), daysAgo(5), NOW)).isTrue();
	}

	@Test
	void 티어_경계는_상한_포함이다() {
		// 나이 딱 30일 → 3일 주기 티어(30 < age 아님), 딱 90일 → 7일 주기, 딱 180일 → 30일 주기
		assertThat(BrandCrawlPolicy.due(daysAgo(30), daysAgo(3), NOW)).isTrue();
		assertThat(BrandCrawlPolicy.due(daysAgo(30), daysAgo(2), NOW)).isFalse();
		assertThat(BrandCrawlPolicy.due(daysAgo(90), daysAgo(7), NOW)).isTrue();
		assertThat(BrandCrawlPolicy.due(daysAgo(90), daysAgo(6), NOW)).isFalse();
		assertThat(BrandCrawlPolicy.due(daysAgo(180), daysAgo(30), NOW)).isTrue();
		assertThat(BrandCrawlPolicy.due(daysAgo(180), daysAgo(29), NOW)).isFalse();
	}
}
```

- [ ] **Step 2: 테스트가 실패하는지 확인**

Run: `./gradlew :monitoring:compileTestJava`
Expected: FAIL — `BrandCrawlPolicy` 심볼 없음 (컴파일 에러)

- [ ] **Step 3: 최소 구현 작성**

```java
package com.celfit.monitoring.service;

import java.time.Duration;
import java.time.Instant;

/**
 * 브랜드 크롤링 정책 v1(2026-08-09 스펙 §3) — 게시물 나이 기반 티어의 순수 함수 판정.
 * 입력은 taken_at·last_crawled_at·현재 시각 3개뿐, 저장된 티어 상태 없음(정책 원칙).
 * 티어 경계·주기는 정책 상수라 코드에 둔다(런타임 토글 불필요 — v1.1 적응형 조정이 오면
 * 이 클래스만 바뀐다). 판정 기준은 항상 taken_at(게시물 나이)이다 — 발견 시각이 아니다.
 */
public final class BrandCrawlPolicy {

	/** 매일 티어 상한(0~14일) — 스윕 최소 열거 깊이이기도 하다(신규 태그 발견 보장 — 스펙 §4). */
	public static final Duration DAILY_MAX_AGE = Duration.ofDays(14);

	/** 추적 상한(180일) — 초과 게시물은 발견 시 스냅샷 1회로 종료(영구 제외 — 스펙 §3·§4). */
	public static final Duration TRACKED_MAX_AGE = Duration.ofDays(180);

	private static final Duration TIER2_MAX_AGE = Duration.ofDays(30);
	private static final Duration TIER2_INTERVAL = Duration.ofDays(3);
	private static final Duration TIER3_MAX_AGE = Duration.ofDays(90);
	private static final Duration TIER3_INTERVAL = Duration.ofDays(7);
	private static final Duration TIER4_INTERVAL = Duration.ofDays(30);

	private BrandCrawlPolicy() {}

	/**
	 * 이 게시물이 지금 갱신 기한(due)인가 — 나이 티어별 last_crawled_at 경과 판정.
	 * last_crawled_at null은 추적 범위 안에서 무조건 due(마이그레이션 직후 기존 행·미완
	 * 수집분의 안전 수렴 — 스펙 §3·§6).
	 */
	public static boolean due(Instant takenAt, Instant lastCrawledAt, Instant now) {
		Duration age = Duration.between(takenAt, now);
		if (age.compareTo(TRACKED_MAX_AGE) > 0) {
			return false;
		}
		if (lastCrawledAt == null) {
			return true;
		}
		if (age.compareTo(DAILY_MAX_AGE) <= 0) {
			return true;
		}
		Duration sinceCrawl = Duration.between(lastCrawledAt, now);
		if (age.compareTo(TIER2_MAX_AGE) <= 0) {
			return sinceCrawl.compareTo(TIER2_INTERVAL) >= 0;
		}
		if (age.compareTo(TIER3_MAX_AGE) <= 0) {
			return sinceCrawl.compareTo(TIER3_INTERVAL) >= 0;
		}
		return sinceCrawl.compareTo(TIER4_INTERVAL) >= 0;
	}
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :monitoring:test --tests "com.celfit.monitoring.service.BrandCrawlPolicyTest"`
Expected: PASS (9개 전부)

- [ ] **Step 5: 커밋**

```bash
git add monitoring/src/main/java/com/celfit/monitoring/service/BrandCrawlPolicy.java monitoring/src/test/java/com/celfit/monitoring/service/BrandCrawlPolicyTest.java
git commit -m "feat(monitoring): BrandCrawlPolicy — 나이 기반 티어 순수 함수 판정(정책 v1 스펙 §3)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 2: `last_crawled_at` 마이그레이션 + `TaggedPostRepository` 확장

**Files:**
- Create: `monitoring/src/main/resources/db/migration/V20260809120000__brand_tagged_post_last_crawled_at.sql`
- Modify: `monitoring/src/main/java/com/celfit/monitoring/store/TaggedPostRepository.java`
- Test: `monitoring/src/test/java/com/celfit/monitoring/store/BrandStoreTest.java` (테스트 추가)

**Interfaces:**
- Consumes: 없음 (Task 1과 독립)
- Produces:
  - `public record TrackedPost(String shortCode, Instant takenAt, Instant lastCrawledAt)` — `TaggedPostRepository` 중첩 record
  - `public List<TrackedPost> trackedPosts(long brandId, Instant minTakenAt)` — taken_at ≥ minTakenAt인 링크 전부
  - `public void touchCrawled(long brandId, Collection<String> codes, Instant at)` — 배치 UPDATE
  - DB 컬럼 `brand_tagged_post.last_crawled_at timestamptz` (nullable)

- [ ] **Step 1: 마이그레이션 파일 작성**

`monitoring/src/main/resources/db/migration/V20260809120000__brand_tagged_post_last_crawled_at.sql`:

```sql
-- 브랜드 크롤링 정책 v1(2026-08-09 스펙 §6) — 나이 기반 티어 판정의 게시물별 마지막 수집 시각.
-- null = 아직 티어 크롤 이전(기존 행·미완 수집분) → 판정식(BrandCrawlPolicy)이 무조건 due로
-- 취급해 첫 스윕에서 자연 수렴한다(백필 UPDATE 불필요). expand 단계 — 참조 코드와 같은 PR.
ALTER TABLE brand_tagged_post ADD COLUMN last_crawled_at timestamptz;
```

- [ ] **Step 2: 실패하는 테스트 작성**

`BrandStoreTest.java` 기존 테스트들 뒤에 추가 (기존 `profile(...)`·`post(String code, long takenAt)` 헬퍼 재사용):

```java
	// ── 티어 판정 입력(정책 v1 — 2026-08-09 스펙 §6) ─────────────────────────

	@Test
	void 링크_last_crawled_at은_null로_시작하고_touchCrawled가_갱신한다() {
		long id = brands.insertOrReactivate("brandx", profile("brandx", "111", 1000L, "소개"));
		taggedPosts.insert(id, post("A", 1754000000L));
		taggedPosts.insert(id, post("B", 1754000000L));
		Instant floor = Instant.ofEpochSecond(1754000000L).minusSeconds(60);

		assertThat(taggedPosts.trackedPosts(id, floor)).hasSize(2)
				.allMatch(t -> t.lastCrawledAt() == null);

		Instant at = Instant.parse("2026-08-09T03:00:00Z");
		taggedPosts.touchCrawled(id, List.of("A"), at);

		List<TaggedPostRepository.TrackedPost> after = taggedPosts.trackedPosts(id, floor);
		assertThat(after).filteredOn(t -> t.shortCode().equals("A"))
				.singleElement().satisfies(t -> assertThat(t.lastCrawledAt()).isEqualTo(at));
		assertThat(after).filteredOn(t -> t.shortCode().equals("B"))
				.singleElement().satisfies(t -> assertThat(t.lastCrawledAt()).isNull());
	}

	@Test
	void trackedPosts는_minTakenAt_이전_링크를_거른다() {
		// 추적 플로어(180일) 밖 링크는 티어 판정 입력에서 빠진다 — 영구 제외의 조회 측 절반
		long id = brands.insertOrReactivate("brandx", profile("brandx", "111", 1000L, "소개"));
		taggedPosts.insert(id, post("Recent", 1754000000L));
		taggedPosts.insert(id, post("Ancient", 1700000000L));

		assertThat(taggedPosts.trackedPosts(id, Instant.ofEpochSecond(1750000000L)))
				.extracting(TaggedPostRepository.TrackedPost::shortCode).containsExactly("Recent");
	}

	@Test
	void touchCrawled는_빈_목록에_쿼리를_내지_않는다() {
		long id = brands.insertOrReactivate("brandx", profile("brandx", "111", 1000L, "소개"));
		taggedPosts.touchCrawled(id, List.of(), Instant.now());   // 예외 없이 no-op이면 통과
	}
```

- [ ] **Step 3: 테스트가 실패하는지 확인**

Run: `./gradlew :monitoring:compileTestJava`
Expected: FAIL — `trackedPosts`·`touchCrawled`·`TrackedPost` 심볼 없음

- [ ] **Step 4: 리포지토리 구현**

`TaggedPostRepository.java`에 추가 (`java.time.Instant`·`java.util.List`는 이미 import 확인 — 없으면 추가):

```java
	/** 티어 판정 입력 행 — 판정 자체는 BrandCrawlPolicy 순수 함수가 한다(스펙 §3). */
	public record TrackedPost(String shortCode, Instant takenAt, Instant lastCrawledAt) {}

	/** 추적 범위(taken_at ≥ minTakenAt) 링크 전부 — 스윕의 열거 깊이 결정 입력(스펙 §4). */
	public List<TrackedPost> trackedPosts(long brandId, Instant minTakenAt) {
		return db.query("""
				SELECT short_code, taken_at, last_crawled_at FROM brand_tagged_post
				WHERE brand_id = ? AND taken_at >= ?""",
				(rs, i) -> {
					Timestamp last = rs.getTimestamp("last_crawled_at");
					return new TrackedPost(rs.getString("short_code"),
							rs.getTimestamp("taken_at").toInstant(),
							last == null ? null : last.toInstant());
				}, brandId, Timestamp.from(minTakenAt));
	}

	/** 이번 열거에서 만난 게시물의 마지막 수집 시각 배치 갱신 — 다음 스윕의 티어 판정 입력. */
	public void touchCrawled(long brandId, Collection<String> codes, Instant at) {
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
		db.update("UPDATE brand_tagged_post SET last_crawled_at = ? WHERE brand_id = ? AND short_code IN ("
				+ placeholders + ")", args);
	}
```

- [ ] **Step 5: 테스트 통과 확인**

Run: `./gradlew :monitoring:test --tests "com.celfit.monitoring.store.BrandStoreTest" --tests "com.celfit.monitoring.MigrationTest"`
Expected: PASS — 신규 3개 포함 전부. (Testcontainers 사용 — `DOCKER_HOST` 미설정 상태로 Docker Desktop 기동 확인)

- [ ] **Step 6: 커밋**

```bash
git add monitoring/src/main/resources/db/migration/V20260809120000__brand_tagged_post_last_crawled_at.sql monitoring/src/main/java/com/celfit/monitoring/store/TaggedPostRepository.java monitoring/src/test/java/com/celfit/monitoring/store/BrandStoreTest.java
git commit -m "feat(monitoring): brand_tagged_post.last_crawled_at + trackedPosts·touchCrawled — 티어 판정 입력(정책 v1 스펙 §6)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 3: `BrandCollectService` 티어 배선 — 열거 깊이·365일 편입·안전 상한

**Files:**
- Modify: `monitoring/src/main/java/com/celfit/monitoring/service/BrandCollectService.java`
- Modify: `monitoring/src/main/resources/application.yml` (brand 블록)
- Test: `monitoring/src/test/java/com/celfit/monitoring/service/BrandCollectServiceTest.java` (개편)
- Modify: `monitoring/src/test/java/com/celfit/monitoring/service/BrandRegistrationServiceTest.java:86` (스텁 생성자 인자)
- Modify: `monitoring/src/test/java/com/celfit/monitoring/service/BrandSweepJobTest.java:62` (스텁 생성자 인자)

**Interfaces:**
- Consumes: `BrandCrawlPolicy.due(...)`·`DAILY_MAX_AGE`·`TRACKED_MAX_AGE` (Task 1), `TaggedPostRepository.trackedPosts(...)`·`touchCrawled(...)`·`TrackedPost` (Task 2), `BrandRow.lastSweptOn()` (기존)
- Produces: `BrandCollectService` 생성자 시그니처 변경 — `@Value` 파라미터가 `(…, int registrationWindowDays, int maxPostsPerSweep, int commentPages, int authorStaleDays)`로 (기존 windowDays·windowPosts 대체). `sweep`/`sweepCore`/`enrich` 공개 시그니처는 불변 — `BrandRegistrationService`·`BrandSweepJob`은 무수정.

- [ ] **Step 1: 실패하는 테스트 작성 — `BrandCollectServiceTest` 개편**

다음 순서로 수정한다:

1-a. 상수 교체·추가 (기존 `OLD_95D` 유지):

```java
	private static final long NOW = Instant.now().getEpochSecond();
	private static final long RECENT = NOW - 5L * 86400;             // 매일 티어(0~14일) 안
	private static final long OLD_20D = NOW - 20L * 86400;           // 14일 컷 밖, 추적(180일) 안
	private static final long RETRO_IN_WINDOW = NOW - 60L * 86400;   // 소급 태그(7일 주기 티어)
	private static final long OLD_70D = NOW - 70L * 86400;           // 60일 컷 이전 판정용
	private static final long OLD_95D = NOW - 95L * 86400;           // 구 90일 윈도우 밖·365일 안(백필 편입)
	private static final long OLD_200D = NOW - 200L * 86400;         // 추적 종료 구간(180~365일)
	private static final long OLD_400D = NOW - 400L * 86400;         // 편입 컷(365일) 밖
```

1-b. 브랜드 픽스처 — 기존 `brand`(lastSweptOn null = 백필 경로) 옆에 티어 경로용 추가:

```java
	private final BrandRow brand = new BrandRow(1L, "brandx", "111", BrandStatus.ACTIVE, null);
	// 완주 이력 있는 브랜드 — 티어 경로(백필 아님). 어제 완주로 두어 오늘 스윕 시나리오를 만든다.
	private final BrandRow sweptBrand = new BrandRow(1L, "brandx", "111", BrandStatus.ACTIVE,
			LocalDate.now().minusDays(1));
```

1-c. `InMemoryTagged`에 신규 메서드 대역 추가 (기존 필드·오버라이드 유지):

```java
		final List<TaggedPostRepository.TrackedPost> tracked = new ArrayList<>();
		final Map<String, Instant> touched = new HashMap<>();

		@Override
		public List<TaggedPostRepository.TrackedPost> trackedPosts(long brandId, Instant minTakenAt) {
			return tracked.stream().filter(t -> !t.takenAt().isBefore(minTakenAt)).toList();
		}

		@Override
		public void touchCrawled(long brandId, Collection<String> codes, Instant at) {
			for (String c : codes) {
				touched.put(c, at);
			}
		}
```

1-d. `service(int)` 헬퍼의 의미를 windowPosts→maxPostsPerSweep로 교체 (registrationWindowDays=365):

```java
	private BrandCollectService service(int maxPostsPerSweep) {
		return new BrandCollectService(client(), writer, snapshots, comments, tagged, authors,
				Runnable::run, 365, maxPostsPerSweep, 3, 30);
	}
```

같은 파일 맨 아래 `보강_게시자_콜은_워커_풀_동시성으로…` 테스트의 직접 생성자 호출도 `pool, 365, 2000, 3, 30`으로 교체.

1-e. 기존 테스트 3개 개정 (나머지 기존 테스트는 무수정 — RECENT만 쓰므로 365일 컷·백필 경로에서 동작 불변):

```java
	@Test
	void 안전_상한_도달_시_열거를_중단한다() {   // 구 "스윕은_목표_개수까지_커서를_추종한다" 개칭
		tagPages.add(page("p2", reel("A", RECENT, 0, 101, ""), reel("B", RECENT, 0, 102, "")));
		tagPages.add(page("p3", reel("C", RECENT, 0, 103, ""), reel("D", RECENT, 0, 104, "")));
		tagPages.add(page(null, reel("E", RECENT, 0, 105, "")));

		service(3).sweep(brand);   // 상한 3 — 2페이지째에서 4개 도달, 3페이지는 부르지 않는다

		assertThat(tagCalls()).isEqualTo(2);
		assertThat(tagged.inserted).containsExactlyInAnyOrder("A", "B", "C", "D");
	}

	@Test
	void 스윕은_페이지_전체가_컷_이전이면_중단한다() {
		// due 없는 티어 경로 — 컷은 최소 깊이 14일. 2페이지 전체가 컷 이전이라 중단하되,
		// 이미 실려 온 20일령 게시물은 365일 편입 컷 안이므로 적재는 된다(공짜 데이터 — 스펙 §4).
		tagPages.add(page("p2", reel("A", RECENT, 0, 101, "")));
		tagPages.add(page("p3", reel("Old1", OLD_20D, 0, 102, ""), reel("Old2", OLD_20D, 0, 103, "")));
		tagPages.add(page(null, reel("NeverFetched", RECENT, 0, 104, "")));

		service(2000).sweep(sweptBrand);

		assertThat(tagCalls()).isEqualTo(2);
		assertThat(tagged.inserted).containsExactlyInAnyOrder("A", "Old1", "Old2");
	}

	@Test
	void 윈도우_밖_게시물은_적재하지_않는다() {
		// 편입 컷은 365일(정책 §2 최대 12개월) — 그 밖과 taken_at 미상만 제외된다.
		tagPages.add(page(null, reel("Old400", OLD_400D, 0, 101, ""), reel("NoTakenAt", null, 0, 102, "")));

		service(2000).sweep(brand);

		assertThat(writer.saved).isEmpty();
		assertThat(tagged.inserted).isEmpty();
	}
```

1-f. 신규 테스트 5개 추가:

```java
	// ── 티어 깊이 결정(정책 v1 — 2026-08-09 스펙 §4) ─────────────────────────

	@Test
	void due_없으면_최소_14일_깊이만_연다() {
		// 20일령 링크가 있지만 어제 크롤됨(3일 주기 미경과) — 컷은 14일 유지
		tagged.tracked.add(new TaggedPostRepository.TrackedPost("Fresh20d",
				Instant.ofEpochSecond(OLD_20D), Instant.ofEpochSecond(NOW - 86400)));
		tagPages.add(page("p2", reel("A", RECENT, 0, 101, "")));
		tagPages.add(page("p3", reel("Old1", OLD_20D, 0, 102, "")));
		tagPages.add(page(null, reel("NeverFetched", RECENT, 0, 103, "")));

		service(2000).sweep(sweptBrand);

		assertThat(tagCalls()).isEqualTo(2);   // 2페이지 전체가 14일 컷 이전 — 중단
	}

	@Test
	void due_게시물의_taken_at까지_깊이를_늘린다() {
		// 60일령, 마지막 크롤 10일 전(≥ 7일 주기) — due. 컷이 60일로 내려간다.
		tagged.tracked.add(new TaggedPostRepository.TrackedPost("Due60d",
				Instant.ofEpochSecond(RETRO_IN_WINDOW), Instant.ofEpochSecond(NOW - 10L * 86400)));
		tagPages.add(page("p2", reel("A", RECENT, 0, 101, "")));
		tagPages.add(page("p3", reel("Old1", OLD_20D, 0, 102, "")));    // 컷(60일) 이후 — 계속
		tagPages.add(page(null, reel("Deep", OLD_70D, 0, 103, "")));    // 전체가 컷 이전 — 중단

		service(2000).sweep(sweptBrand);

		assertThat(tagCalls()).isEqualTo(3);   // due 없던 위 테스트(2콜)와 대조 — 깊이가 늘었다
		assertThat(tagged.inserted).containsExactlyInAnyOrder("A", "Old1", "Deep");
	}

	@Test
	void 백필은_365일_전체를_연다() {
		// last_swept_on null(등록 직후·백필 실패 백스톱·재가입) — due 판정 없이 등록 윈도우 전체
		tagPages.add(page("p2", reel("A", RECENT, 0, 101, "")));
		tagPages.add(page(null, reel("Old95", OLD_95D, 0, 102, "")));

		service(2000).sweep(brand);

		assertThat(tagCalls()).isEqualTo(2);
		assertThat(tagged.inserted).containsExactlyInAnyOrder("A", "Old95");   // 95일령도 편입
	}

	@Test
	void 늦은_발견은_나이에_맞게_편입한다() {
		// 180~365일: 링크+스냅샷 1회(이후 due 판정이 영구 제외 — BrandCrawlPolicyTest가 고정).
		// 365일 초과: 무시(정책 §2 최대 12개월).
		tagPages.add(page(null, reel("A", RECENT, 0, 101, ""),
				reel("Retro200", OLD_200D, 0, 102, ""), reel("Ancient400", OLD_400D, 0, 103, "")));

		service(2000).sweep(sweptBrand);

		assertThat(tagged.inserted).containsExactlyInAnyOrder("A", "Retro200");
		assertThat(writer.saved).extracting(PostInfo::shortCode)
				.containsExactlyInAnyOrder("A", "Retro200");
	}

	@Test
	void 만난_게시물은_last_crawled_at을_갱신한다() {
		tagged.known.add("KnownA");
		tagPages.add(page(null, reel("KnownA", RECENT, 0, 101, ""), reel("NewB", RECENT, 0, 102, "")));

		service(2000).sweep(sweptBrand);

		assertThat(tagged.touched).containsKeys("KnownA", "NewB");
	}
```

1-g. 다른 테스트 파일의 스텁 생성자 인자 교체 (컴파일 유지 목적, 값 의미: 365=registrationWindowDays, 2000=maxPostsPerSweep):
- `BrandRegistrationServiceTest.java:86`: `super(null, null, null, null, null, null, null, 90, 105, 3, 30);` → `super(null, null, null, null, null, null, null, 365, 2000, 3, 30);`
- `BrandSweepJobTest.java:62`: 동일 교체.

- [ ] **Step 2: 테스트가 실패하는지 확인**

Run: `./gradlew :monitoring:compileTestJava`
Expected: FAIL — 생성자 인자 불일치(구현이 아직 windowDays·windowPosts) 컴파일 에러

- [ ] **Step 3: `BrandCollectService` 구현 수정**

3-a. 필드·생성자 — `windowDays`·`windowPosts`를 교체:

```java
	private final int registrationWindowDays;
	private final int maxPostsPerSweep;
```

```java
			@Value("${monitoring.brand.registration-window-days:365}") int registrationWindowDays,
			@Value("${monitoring.brand.max-posts-per-sweep:2000}") int maxPostsPerSweep,
			@Value("${monitoring.brand.comment-pages:3}") int commentPages,
			@Value("${monitoring.brand.author-stale-days:30}") int authorStaleDays) {
```

(대입문도 동일하게 교체.)

3-b. `sweepCore` — 깊이 결정을 티어 기반으로, 개수 상한을 안전 밸브로:

```java
	/**
	 * core 단계 — ①브랜드 프로필 1콜(매일 갱신 + 추이 적재, best-effort) ②태그 열거를 오늘의
	 * 깊이 컷({@link #enumerationCutoff})까지 next_page_id 추종 ③편입 컷(365일) 안 전 게시물
	 * 스냅샷·메타 적재 + 신규 링크 + last_crawled_at 갱신. 여기까지가 브랜드 화면 목록 렌더에
	 * 필요한 전부다 → ready(touchSwept)는 이 반환 직후 찍어도 된다.
	 *
	 * <p>열거 중단: ①페이지 전체가 깊이 컷 이전(소급 태그 혼입 때문에 "오래된 글 1건 발견 즉시
	 * 중단" 금지 — 08-06 스펙 §5) ②커서 소진 ③커서 미전진 ④안전 상한(maxPostsPerSweep) 도달 —
	 * 개수 상한은 폐지됐고(정책 v1 §4) 이 값은 폭주 방어 밸브다(정상 경로에서 닿으면 안 된다).
	 *
	 * @return 편입 컷 안 게시물(복권 지표 보정 후) — {@link #enrich}가 재열거 없이 그대로 소비한다.
	 */
	public List<PostInfo> sweepCore(BrandRow brand) {
		refreshBrandProfileSafely(brand);
		Instant now = Instant.now();
		Instant cutoff = enumerationCutoff(brand, now);
		Map<String, PostInfo> byCode = new LinkedHashMap<>();
		String cursor = null;
		while (true) {
			HikerClient.TaggedPage page = hiker.fetchTaggedPage(brand.igUserId(), cursor);
			if (page.posts().isEmpty()) {
				break;
			}
			int before = byCode.size();
			page.posts().forEach(p -> byCode.putIfAbsent(p.shortCode(), p));
			if (byCode.size() >= maxPostsPerSweep) {
				log.warn("태그 열거 안전 상한({}) 도달 — 브랜드 {} 정상 경로에서 닿으면 안 되는 값, 열거 중단",
						maxPostsPerSweep, brand.username());
				break;
			}
			// taken_at 미상 아이템은 "컷 이전" 판정에 넣지 않는다(보수적으로 열거 계속).
			boolean wholePageBeforeCutoff = page.posts().stream()
					.allMatch(p -> p.takenAt() != null
							&& Instant.ofEpochSecond(p.takenAt()).isBefore(cutoff));
			if (wholePageBeforeCutoff || page.nextPageId() == null) {
				break;
			}
			if (byCode.size() == before) {
				log.warn("태그 커서 미전진 의심 — 브랜드 {} 신규 code 0건, 열거 중단", brand.username());
				break;
			}
			cursor = page.nextPageId();
		}
		return processCore(brand, List.copyOf(byCode.values()), now);
	}
```

3-c. 깊이 결정 메서드 신설 (`TaggedPostRepository.TrackedPost` import 또는 정규화된 이름 사용):

```java
	/**
	 * 오늘의 열거 깊이 컷(정책 v1 스펙 §4) — 백필(last_swept_on null: 등록 직후·백필 실패
	 * 백스톱·재가입)은 등록 윈도우(365일) 전체, 이후엔 min(14일 컷, 가장 오래된 due 게시물의
	 * taken_at). due 판정은 {@link BrandCrawlPolicy} 순수 함수 — 깊은 열거가 얕은 티어를 자동
	 * 포함하므로 정책의 중복 제거 규칙이 구조적으로 성립하고, 스윕이 하루 빠져도 다음 날 due
	 * 계산이 밀린 깊이까지 자동 커버한다(자가 치유).
	 */
	private Instant enumerationCutoff(BrandRow brand, Instant now) {
		if (brand.lastSweptOn() == null) {
			return now.minus(Duration.ofDays(registrationWindowDays));
		}
		Instant cutoff = now.minus(BrandCrawlPolicy.DAILY_MAX_AGE);
		for (TaggedPostRepository.TrackedPost t : taggedPosts.trackedPosts(brand.id(),
				now.minus(BrandCrawlPolicy.TRACKED_MAX_AGE))) {
			if (t.takenAt().isBefore(cutoff)
					&& BrandCrawlPolicy.due(t.takenAt(), t.lastCrawledAt(), now)) {
				cutoff = t.takenAt();
			}
		}
		return cutoff;
	}
```

3-d. `processCore` — 시그니처에 `now` 추가, 편입 컷 365일, `touchCrawled` 배선. 기존 `windowCutoff()` 메서드는 삭제:

```java
	/** core 열거 결과 처리 — 편입 컷(365일) 필터 → 복권 지표 보정 → 스냅샷·메타 적재 → 신규 링크 → last_crawled_at 갱신. */
	private List<PostInfo> processCore(BrandRow brand, List<PostInfo> posts, Instant now) {
		Instant enrollCutoff = now.minus(Duration.ofDays(registrationWindowDays));
		// taken_at 미상은 보수적으로 제외(잘못된 편입 방지) — 다음 열거에서 채워지면 잡힌다.
		List<PostInfo> inWindow = posts.stream()
				.filter(p -> p.takenAt() != null
						&& !Instant.ofEpochSecond(p.takenAt()).isBefore(enrollCutoff))
				.toList();
		if (inWindow.isEmpty()) {
			return List.of();
		}
		Set<String> known = taggedPosts.knownCodes(brand.id());
		Set<String> freshCodes = inWindow.stream().map(PostInfo::shortCode)
				.filter(c -> !known.contains(c))
				.collect(Collectors.toCollection(LinkedHashSet::new));
		List<PostInfo> adjusted = adjustLotteryMetrics(inWindow);
		LocalDate today = LocalDate.now(KST);
		for (PostInfo p : adjusted) {
			writer.savePost(today, p);
		}
		for (PostInfo p : adjusted) {
			if (freshCodes.contains(p.shortCode())) {
				taggedPosts.insert(brand.id(), p);
			}
		}
		// 만난 게시물 전부(신규 포함) — 다음 스윕의 티어 판정(due) 입력. 180일 초과분 갱신도
		// 무해하다(판정식이 영구 제외라 이들을 위한 콜은 발생하지 않는다 — 스펙 §4).
		taggedPosts.touchCrawled(brand.id(),
				adjusted.stream().map(PostInfo::shortCode).toList(), now);
		log.info("브랜드 태그 수집 — {} 열거 {}건, 편입 컷 안 {}건, 신규 {}건",
				brand.username(), posts.size(), inWindow.size(), freshCodes.size());
		return adjusted;
	}
```

3-e. 클래스 javadoc의 "105개 깊이·매일 전량" 서술을 갱신 — 첫 문단을 다음으로 교체(나머지 문단 유지):

```java
/**
 * 브랜드 태그 수집 본체(2026-08-06 스펙 + 2026-08-09 크롤링 정책 v1) — 태그 열거 단일 경로
 * (/v2/user/tag/medias)로 수집하되, 깊이는 게시물 나이 티어({@link BrandCrawlPolicy})가 정한다:
 * 매일 최소 14일 깊이(신규 감지 겸용) + due 게시물이 있으면 그 taken_at까지 확장, 등록 백필은
 * 365일 전체. 단건 게시물 콜은 전면 금지 유지(08-06 실측 — 열거 대비 추가 지표 없음).
 */
```

3-f. `application.yml` brand 블록 — 주석·키 교체:

```yaml
  # 브랜드 태그 모니터링(2026-08-06 스펙 + 2026-08-09 크롤링 정책 v1) — 태그 열거 단일 경로,
  # 게시물 나이 티어 주기(BrandCrawlPolicy: 14일 매일 / 30일 3일 / 90일 7일 / 180일 30일 주기).
  brand:
    schedule:
      sweep-cron: "-"   # "-"=비활성. 운영은 캠페인 스윕(KST 02:00)과 겹치지 않게 UTC 18:00(KST 03:00) env 주입
    registration-window-days: 365   # 등록 백필·신규 편입 컷(정책 §2 — 최대 12개월). 스윕 깊이는 티어 due가 결정
    max-posts-per-sweep: 2000       # 개수 상한 폐지(정책 §4) — 폭주 방어 안전 밸브만(도달 시 경고 로그)
    comment-pages: 3            # 게시물당 댓글 상한 3콜 45개(스펙 §2 — 정책 v1에서 30일 보충 없이 유지 확정)
    author-stale-days: 30       # 게시자 프로필 등장 시 stale 갱신 기준(스펙 §8)
    enrich-concurrency: 6       # 보강(게시자·댓글) 워커 풀 크기 — 08-07 운영 실측(동시 8 무저항)에서 마진 둔 값
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :monitoring:test --tests "com.celfit.monitoring.service.*"`
Expected: PASS — BrandCollectServiceTest(개편분 포함)·BrandRegistrationServiceTest·BrandSweepJobTest·BrandCrawlPolicyTest 전부

- [ ] **Step 5: 커밋**

```bash
git add monitoring/src/main/java/com/celfit/monitoring/service/BrandCollectService.java monitoring/src/main/resources/application.yml monitoring/src/test/java/com/celfit/monitoring/service/BrandCollectServiceTest.java monitoring/src/test/java/com/celfit/monitoring/service/BrandRegistrationServiceTest.java monitoring/src/test/java/com/celfit/monitoring/service/BrandSweepJobTest.java
git commit -m "feat(monitoring): 브랜드 스윕 티어 배선 — 나이 기반 열거 깊이·365일 백필·안전 상한(정책 v1 스펙 §4~§6)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 4: 모듈 전체 검증 + 문서 반영

**Files:**
- Modify: `DECISIONS.md` (맨 위에 새 결정 행)
- Modify: `docs/tracks/MON-BT-브랜드-태그-모니터링.md` (상태 갱신)
- Modify: `docs/superpowers/specs/2026-08-09-brand-crawl-policy-v1-design.md` (상태 헤더 → ✅ 구현됨)

**Interfaces:**
- Consumes: Task 1~3 완료 상태
- Produces: 없음 (검증·기록)

- [ ] **Step 1: 모듈 전체 테스트**

Run: `./gradlew :monitoring:test`
Expected: PASS 전부. 실패 시 원인 수정 후 재실행(수정분은 해당 태스크 커밋에 fixup하지 말고 별도 `fix(monitoring):` 커밋).

- [ ] **Step 2: DECISIONS.md 맨 위에 결정 추가**

기존 최신 행 형식을 확인하고 같은 형식으로, 요지는 다음 내용을 담는다:

> **2026-08-09 — 브랜드 크롤링 정책 v1(나이 기반 티어) 반영**: 매일 전량(90일 & 105개) →
> 게시물 나이 티어 주기(14일 매일 / 30일 3일 / 90일 7일 / 180일 30일 / 초과 영구 제외) +
> 등록 백필 365일·개수 상한 폐지(안전 밸브 2000). 판정은 BrandCrawlPolicy 순수 함수
> (taken_at·last_crawled_at, 저장 티어 상태 없음), 열거 깊이로 번역. 단건 상세 콜 금지·복권
> 3종 기회 적재·댓글 45개 상한은 유지 확정(사용자 결정 — 정책 문서의 상세 847콜·30일 댓글
> 보충·부스트 크롤은 채택 안 함). 스펙: docs/superpowers/specs/2026-08-09-brand-crawl-policy-v1-design.md

- [ ] **Step 3: 트랙 문서 갱신**

`docs/tracks/MON-BT-브랜드-태그-모니터링.md`에 정책 v1 반영 항목 추가(기존 문서의 항목 형식을 따라, 위 결정 요지 + 구현 커밋 언급).

- [ ] **Step 4: 스펙 상태 헤더 갱신**

`2026-08-09-brand-crawl-policy-v1-design.md` 첫머리 `> 상태: 🟢 활성` → `> 상태: ✅ 구현됨 (2026-08-09)`.

- [ ] **Step 5: 커밋**

```bash
git add DECISIONS.md docs/tracks/MON-BT-브랜드-태그-모니터링.md docs/superpowers/specs/2026-08-09-brand-crawl-policy-v1-design.md
git commit -m "docs: 브랜드 크롤링 정책 v1 반영 결정·트랙 갱신, 스펙 ✅ 전환

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## 스펙 커버리지 자가 점검 (계획 작성 시 수행 완료)

- 스펙 §3 판정식 → Task 1 / §4 깊이·중단·편입 규칙 → Task 3 / §5 등록 백필 365일 → Task 3(`enumerationCutoff`의 lastSweptOn null 분기 — `BrandRegistrationService`·재가입 초기화가 기존에 null을 보장) / §6 스키마·설정 → Task 2·3 / §7 변경 없음 목록 → 어느 태스크도 해당 코드 경로를 건드리지 않음 / §9 테스트 → Task 1~3 / §8 비용 재산정 표의 게시자 N 실측 갱신은 운영 배포 후 별도(코드 아님).
- 부스트 크롤·30일 댓글 보충·단건 상세 콜은 **의도적으로 태스크 없음**(사용자 확정 — 채택 안 함).
