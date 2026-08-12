# 브랜드 수집 범위 선택(collectionMonths) 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

> 상태: ✅ 구현/실행/반영됨 (2026-08-12, 태스크 1~7 완료) · 스펙: [2026-08-12-brand-collection-months-design.md](../../specs/2026-08-12-brand-collection-months-design.md)

**Goal:** 브랜드 계정 등록에 수집 범위(1/3/6/12개월)를 도입하고, 더 큰 값 재등록을 기간 확장(증분 수집)으로 처리하며, 계정 응답에 `collectionMonths`를 싣는다.

**Architecture:** monitoring `brand_account`에 자산 레벨 `collection_months`(max로만 커짐)·`collection_started_at`을 추가하고, 수집 창(열거 컷·편입 컷)을 전역 설정 대신 이 컬럼으로 계산한다. 확장은 monitoring 등록 replay의 새 분기(`last_swept_on` 클리어 → 기존 스윕 백스톱 상속)이고, was는 자산 값을 읽어 클 때만 monitoring을 재호출하는 사전 게이트다. 확장 중 상태는 was 유도 규칙에 `backfill_completed_at` 분기를 추가해 `collecting`으로 전이한다(데이터는 계속 서빙).

**Tech Stack:** Java 21, Spring Boot 4.1, JdbcTemplate/JdbcClient, Flyway(UTC 타임스탬프 채번), Testcontainers(BrandStoreTest), MockMvc 슬라이스.

## Global Constraints

- 값 공간은 `1 | 3 | 6 | 12`, 생략 시 `12`(하위 호환). 밖이면 was 400 `VALIDATION_FAILED`, monitoring 400.
- `collection_months`는 절대 줄지 않는다(확장은 `>` 판정, 재가입은 `GREATEST`).
- 주석·로그·커밋 메시지는 한국어. 커밋 prefix `feat(모듈):`/`docs:`.
- Flyway 신규 파일은 UTC 타임스탬프 채번(`V20260812…__….sql`), 기존 파일 rename 금지.
- 스키마 변경은 expand-contract — 이번 작업은 신규 컬럼 + DEFAULT/nullable뿐이라 파괴 패턴 없음.
- 테스트는 모듈 단위(`./gradlew :monitoring:test`, `:was:test`). 전체 `./gradlew test`는 PR 직전에만.
- 로컬 도커는 Docker Desktop, `DOCKER_HOST` 미설정이 정답(08-09 확인) — Testcontainers 테스트 전 Docker Desktop 기동 확인.
- 뷰·컬럼·경로 grep 시 `--exclude-dir=docs`.

---

### Task 1: monitoring 마이그레이션 + BrandRow/BrandRepository 확장

**Files:**
- Create: `monitoring/src/main/resources/db/migration/V20260812220000__brand_collection_months.sql`
- Modify: `monitoring/src/main/java/com/celfit/monitoring/store/BrandRow.java`
- Modify: `monitoring/src/main/java/com/celfit/monitoring/store/BrandRepository.java`
- Modify: `monitoring/src/main/java/com/celfit/monitoring/service/BrandRegistrationService.java:107` (호출부 임시 12 — Task 3에서 실값 대체)
- Test: `monitoring/src/test/java/com/celfit/monitoring/store/BrandStoreTest.java`
- Test(기계 갱신): `monitoring/src/test/java/com/celfit/monitoring/service/BrandRegistrationServiceTest.java`, `BrandCollectServiceTest.java`, `BrandHashtagCollectServiceTest.java`, `BrandSweepJobTest.java`

**Interfaces:**
- Produces: `BrandRow(long id, String username, String igUserId, BrandStatus status, LocalDate lastSweptOn, int collectionMonths)`
- Produces: `BrandRepository.insertOrReactivate(String username, ProfileInfo profile, int collectionMonths)` — 재가입 시 `GREATEST`로 창이 줄지 않음
- Produces: `BrandRepository.expandWindow(long brandId, int months)` — 창 상향 + `last_swept_on` NULL + `collection_started_at = now()` + `backfill_error` NULL

- [ ] **Step 1: 실패하는 테스트 작성** — `BrandStoreTest`에 추가(기존 `profile()` 헬퍼 재사용):

```java
@Test
void 수집_창은_요청값으로_저장되고_재가입에도_줄지_않는다() {
	long id = brands.insertOrReactivate("brandx", profile("brandx", "111", 1000L, "소개"), 3);
	assertThat(brands.findByUsername("brandx").orElseThrow().collectionMonths()).isEqualTo(3);
	// 재가입 확대는 반영, 축소는 GREATEST가 막는다 — "수집된 사실이 정본"(스펙 결정 요약).
	brands.close("brandx");
	brands.insertOrReactivate("brandx", profile("brandx", "111", 1000L, "소개"), 6);
	assertThat(brands.findByUsername("brandx").orElseThrow().collectionMonths()).isEqualTo(6);
	brands.close("brandx");
	brands.insertOrReactivate("brandx", profile("brandx", "111", 1000L, "소개"), 1);
	assertThat(brands.findByUsername("brandx").orElseThrow().collectionMonths()).isEqualTo(6);
}

@Test
void 값_공간_밖_수집_창은_CHECK가_거절한다() {
	assertThatThrownBy(() -> brands.insertOrReactivate("brandx",
			profile("brandx", "111", 1000L, "소개"), 2))
			.isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
}

@Test
void expandWindow는_창_상향과_백필_재개_신호를_함께_기록한다() {
	long id = brands.insertOrReactivate("brandx", profile("brandx", "111", 1000L, "소개"), 3);
	brands.touchSwept(id, LocalDate.now());   // 완주 상태를 만들어 둔다
	java.time.OffsetDateTime before = db.queryForObject(
			"SELECT collection_started_at FROM brand_account WHERE id = ?",
			java.time.OffsetDateTime.class, id);

	brands.expandWindow(id, 12);

	BrandRow row = brands.findByUsername("brandx").orElseThrow();
	assertThat(row.collectionMonths()).isEqualTo(12);
	assertThat(row.lastSweptOn()).isNull();   // 백필 재개 신호 — 다음 스윕 백스톱 상속
	assertThat(db.queryForObject("SELECT collection_started_at FROM brand_account WHERE id = ?",
			java.time.OffsetDateTime.class, id)).isAfter(before);   // FE 폴링 앵커 갱신
	assertThat(db.queryForObject("SELECT backfill_completed_at FROM brand_account WHERE id = ?",
			java.time.OffsetDateTime.class, id)).isNotNull();   // 완주 이력은 보존(확장 중 collecting 판별 재료)
}
```

`assertThatThrownBy` static import는 파일에 이미 있는지 확인 후 없으면 추가.

- [ ] **Step 2: 컴파일 실패 확인**

Run: `./gradlew :monitoring:compileTestJava`
Expected: FAIL — `insertOrReactivate(String, ProfileInfo, int)` 미정의, `collectionMonths()` 미정의

- [ ] **Step 3: 마이그레이션 + 구현**

`V20260812220000__brand_collection_months.sql`:

```sql
-- 브랜드 수집 범위 선택(collectionMonths 스펙 2026-08-12) — 자산 레벨 수집 창 + 확장 폴링 앵커.
-- 기존 행은 전부 12개월 수집이었으므로 DEFAULT 12가 사실과 일치하는 백필이다(FE 요청서 §3).
ALTER TABLE brand_account ADD COLUMN collection_months int NOT NULL DEFAULT 12;
ALTER TABLE brand_account ADD CONSTRAINT brand_account_collection_months_chk
    CHECK (collection_months IN (1, 3, 6, 12));
-- FE 수집 폴링(30분 상한)의 앵커 — 확장 시작 시 now()로 갱신된다. nullable 유지(expand-contract),
-- 읽기는 COALESCE(collection_started_at, registered_at)로 접는다.
ALTER TABLE brand_account ADD COLUMN collection_started_at timestamptz;
UPDATE brand_account SET collection_started_at = registered_at;
```

`BrandRow.java` — 필드 끝에 추가(javadoc에 "collectionMonths = 자산 레벨 수집 창(개월), 절대 줄지 않는다" 한 줄):

```java
public record BrandRow(long id, String username, String igUserId, BrandStatus status,
		LocalDate lastSweptOn, int collectionMonths) {}
```

`BrandRepository.java`:
- `findByUsername`·`findActive`의 SELECT에 `collection_months` 추가, `toRow`에 `rs.getInt("collection_months")` 추가.
- `insertOrReactivate` 시그니처에 `int collectionMonths` 추가, SQL 개정(javadoc에 GREATEST 근거 한 줄 추가 — "재가입 축소 요청은 무시한다: 기존 수집분이 이미 있어 창을 줄이면 응답 창과 보유 데이터가 어긋난다"):

```java
public long insertOrReactivate(String username, ProfileInfo profile, int collectionMonths) {
	return db.queryForObject("""
			INSERT INTO brand_account (username, ig_user_id, followers, biography, full_name,
			                           profile_pic_url, is_verified, external_url, following, media_count,
			                           collection_months, collection_started_at)
			VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
			ON CONFLICT (username) DO UPDATE SET
			  ig_user_id = EXCLUDED.ig_user_id, followers = EXCLUDED.followers,
			  biography = EXCLUDED.biography, full_name = EXCLUDED.full_name,
			  profile_pic_url = EXCLUDED.profile_pic_url, is_verified = EXCLUDED.is_verified,
			  external_url = EXCLUDED.external_url, following = EXCLUDED.following,
			  media_count = EXCLUDED.media_count, status = 'ACTIVE', closed_at = NULL,
			  last_swept_on = NULL, backfill_error = NULL, backfill_completed_at = NULL,
			  registered_at = now(),
			  collection_months = GREATEST(brand_account.collection_months, EXCLUDED.collection_months),
			  collection_started_at = now()
			RETURNING id""",
			Long.class, username, profile.userId(), profile.followers(), profile.biography(),
			profile.fullName(), profile.profilePicUrl(), profile.isVerified(), profile.externalUrl(),
			profile.following(), profile.mediaCount(), collectionMonths);
}
```

- 신규 `expandWindow`:

```java
/**
 * 기간 확장(collectionMonths 스펙 §3) — 창 상향 + 백필 재개 신호를 한 UPDATE로.
 * last_swept_on NULL이 핵심이다: 확장 백필이 죽어도 다음 새벽 스윕이 백필 분기(전체 창 열거)로
 * 자동 복구한다(기존 백스톱 상속). backfill_completed_at은 보존한다 — was가 "완주 이력 있는데
 * last_swept_on이 빔 = 확장 중"으로 collecting을 유도하는 판별 재료다.
 */
public void expandWindow(long brandId, int months) {
	db.update("""
			UPDATE brand_account
			SET collection_months = ?, last_swept_on = NULL,
			    collection_started_at = now(), backfill_error = NULL
			WHERE id = ?""", months, brandId);
}
```

`BrandRegistrationService.java:107` — `brands.insertOrReactivate(normalized, profile)` → `brands.insertOrReactivate(normalized, profile, 12)` (동작 불변 임시값, Task 3에서 요청값으로 대체).

- [ ] **Step 4: 기존 테스트 생성부 기계 갱신** (컴파일 회복 — 전부 `12` 부여)
- `BrandRegistrationServiceTest.java` InMemoryBrands:
  - `insertOrReactivate` 오버라이드 시그니처에 `int collectionMonths` 추가, 본문을 `int months = existing != null ? Math.max(existing.collectionMonths(), collectionMonths) : collectionMonths;` 후 `rows.put(username, new BrandRow(id, username, profile.userId(), BrandStatus.ACTIVE, null, months));`로.
  - `close` 오버라이드의 `new BrandRow(...)`에 `row.collectionMonths()` 추가.
- `BrandCollectServiceTest.java:79·81`, `BrandHashtagCollectServiceTest.java:49`, `BrandSweepJobTest.java:146`의 `new BrandRow(...)` 끝에 `, 12` 추가.

- [ ] **Step 5: 테스트 통과 확인**

Run: `./gradlew :monitoring:test --tests "com.celfit.monitoring.store.BrandStoreTest" --tests "com.celfit.monitoring.service.BrandRegistrationServiceTest"`
Expected: PASS (Docker Desktop 필요 — BrandStoreTest는 Testcontainers)

- [ ] **Step 6: Commit**

```bash
git add monitoring/src && git commit -m "feat(monitoring): brand_account 수집 창 컬럼(collection_months·collection_started_at) 도입"
```

---

### Task 2: BrandCollectService — 수집 창을 브랜드별 컬럼으로

**Files:**
- Modify: `monitoring/src/main/java/com/celfit/monitoring/service/BrandCollectService.java`
- Modify: `monitoring/src/main/resources/application.yml:52` (registration-window-days 삭제)
- Test: `monitoring/src/test/java/com/celfit/monitoring/service/BrandCollectServiceTest.java`
- Test(생성자 갱신): `BrandRegistrationServiceTest.java:101`(StubCollect), `BrandSweepJobTest.java:116`

**Interfaces:**
- Consumes: `BrandRow.collectionMonths()` (Task 1)
- Produces: `BrandCollectService` 생성자에서 `registrationWindowDays` 파라미터 제거(12→11개 값 인자). 열거 컷·편입 컷은 `collectionCutoff(brand, now)` = KST 캘린더 `minusMonths(brand.collectionMonths())`

- [ ] **Step 1: 실패하는 테스트 작성** — `BrandCollectServiceTest`에 추가(`백필은_365일_전체를_연다`의 대조군, 같은 페이지 배치):

```java
@Test
void 백필_컷은_브랜드의_collection_months를_따른다() {
	// 3개월 창 브랜드 — 95일령(minusMonths(3)=89~92일보다 항상 과거)만 실린 1페이지는
	// "페이지 전체가 컷 이전"으로 1콜 종료, 편입 컷 밖이라 적재도 0건.
	// 12개월 창 대조군(백필은_365일_전체를_연다)은 같은 배치를 2콜 끝까지 연다.
	BrandRow narrow = new BrandRow(1L, "brandx", "111", BrandStatus.ACTIVE, null, 3);
	tagPages.add(page("p2", reel("Old95a", OLD_95D, 0, 101, ""), reel("Old95b", OLD_95D, 0, 102, "")));
	tagPages.add(page(null, reel("Old95c", OLD_95D, 0, 103, "")));

	service(2000).sweep(narrow);

	assertThat(tagCalls()).isEqualTo(1);
	assertThat(tagged.inserted).isEmpty();
}
```

- [ ] **Step 2: 컴파일·실패 확인**

Run: `./gradlew :monitoring:test --tests "com.celfit.monitoring.service.BrandCollectServiceTest"`
Expected: FAIL — 신규 테스트에서 tagCalls 2·inserted 3건(아직 전역 365일 컷)

- [ ] **Step 3: 구현**

`BrandCollectService.java`:
- 필드·생성자 파라미터 `registrationWindowDays`와 `@Value("${monitoring.brand.registration-window-days:365}")` 제거.
- 헬퍼 추가:

```java
/**
 * 브랜드별 수집 창 컷 — KST 캘린더 개월(요청서 "게시물 taken_at 기준 최근 N개월").
 * 열거 깊이(백필)와 편입 필터가 같은 컷을 쓴다 — 창 밖 소급 태그가 편입되지 않게.
 */
private static Instant collectionCutoff(BrandRow brand, Instant now) {
	return ZonedDateTime.ofInstant(now, KST).minusMonths(brand.collectionMonths()).toInstant();
}
```

(`java.time.ZonedDateTime` import 추가)
- `enumerationCutoff`의 백필 분기: `return now.minus(Duration.ofDays(registrationWindowDays));` → `return collectionCutoff(brand, now);`
- `processPage`: `Instant enrollCutoff = now.minus(Duration.ofDays(registrationWindowDays));` → `Instant enrollCutoff = collectionCutoff(brand, now);`
- 클래스·메서드 javadoc의 "365일"·"등록 윈도우(365일)" 표현을 "브랜드별 수집 창(collection_months)"으로 갱신(§4·§6 컷 서술 포함, 의미 서술은 유지).

`application.yml`: `registration-window-days: 365` 라인 삭제.

- [ ] **Step 4: 테스트 생성자 갱신** — 아래 4곳에서 값 인자 맨 앞의 `365, ` 제거:
- `BrandCollectServiceTest.java:294` (`service()` 헬퍼), `:782` (지연 클라이언트 조립)
- `BrandRegistrationServiceTest.java:101` (StubCollect super)
- `BrandSweepJobTest.java:116` (스텁 super)

- [ ] **Step 5: 테스트 통과 확인**

Run: `./gradlew :monitoring:test --tests "com.celfit.monitoring.service.*"`
Expected: PASS — 신규 테스트 + 기존 365일 계열(months=12는 minusMonths(12)=365~366일이라 OLD_95D 편입·OLD_400D 제외 판정 불변)

- [ ] **Step 6: Commit**

```bash
git add monitoring/src && git commit -m "feat(monitoring): 수집 창을 전역 365일에서 브랜드별 collection_months로 전환"
```

---

### Task 3: monitoring 등록 API — collectionMonths 수용·기간 확장 분기

**Files:**
- Modify: `monitoring/src/main/java/com/celfit/monitoring/service/BrandRegistrationService.java`
- Modify: `monitoring/src/main/java/com/celfit/monitoring/web/BrandController.java`
- Test: `monitoring/src/test/java/com/celfit/monitoring/service/BrandRegistrationServiceTest.java`
- Test: `monitoring/src/test/java/com/celfit/monitoring/web/BrandControllerTest.java`

**Interfaces:**
- Consumes: `BrandRepository.expandWindow`, `insertOrReactivate(…, int)` (Task 1), `runBackfillSafely` (기존 private — 확장이 재사용)
- Produces: `BrandRegistrationService.register(String username, String brandName, Integer collectionMonths)` — null=12, 값 공간 밖 `ValidationException`. 기존 1·2인자 오버로드는 null 위임 유지
- Produces: `BrandController.BrandRegisterRequest(String username, String brandName, Integer collectionMonths)`

- [ ] **Step 1: 실패하는 테스트 작성** — `BrandRegistrationServiceTest`에 추가. 먼저 InMemoryBrands에 기록 필드 추가:

```java
final List<Long> expanded = new ArrayList<>();

@Override
public void expandWindow(long brandId, int months) {
	expanded.add(brandId);
	rows.replaceAll((u, r) -> r.id() == brandId
			? new BrandRow(r.id(), r.username(), r.igUserId(), r.status(), null, months) : r);
}
```

테스트 4건:

```java
@Test
void 더_큰_창_재등록은_확장이다_프로필_콜_없이_백필만_재예약() {
	var first = service().register("brandx", null, 3);
	hikerCalls.clear();
	collect.coreSwept.clear();

	var result = service().register("brandx", null, 12);

	assertThat(result.replayed()).isTrue();
	assertThat(hikerCalls).isEmpty();                            // replay — Hiker 콜 0 유지
	assertThat(brands.expanded).containsExactly(first.brandId());
	assertThat(collect.coreSwept).containsExactly("brandx");     // 동기 executor — 백필 즉시 재실행
	assertThat(brands.rows.get("brandx").collectionMonths()).isEqualTo(12);
}

@Test
void 같거나_작은_창_재등록은_순수_replay다() {
	service().register("brandx", null, 6);
	collect.coreSwept.clear();

	service().register("brandx", null, 6);
	service().register("brandx", null, 3);

	assertThat(brands.expanded).isEmpty();
	assertThat(collect.coreSwept).isEmpty();
	assertThat(brands.rows.get("brandx").collectionMonths()).isEqualTo(6);   // 축소 무시
}

@Test
void 값_공간_밖_collectionMonths는_거절한다() {
	assertThatThrownBy(() -> service().register("brandx", null, 2))
			.isInstanceOf(ValidationException.class);
	assertThat(hikerCalls).isEmpty();   // 검증은 Hiker 콜 도달 전
}

@Test
void collectionMonths_생략은_12다() {
	service().register("brandx");
	assertThat(brands.rows.get("brandx").collectionMonths()).isEqualTo(12);
}
```

(`ValidationException` import 추가: `com.celfit.monitoring.service` 패키지 내라 불필요할 수 있음 — 같은 패키지)

`BrandControllerTest`에 전달 검증 1건 — StubService의 `register` 오버라이드를 3인자로 바꾸고 `Integer receivedMonths` 필드 기록:

```java
Integer receivedMonths;

@Override
public Result register(String username, String brandName, Integer collectionMonths) {
	receivedBrandName = brandName;
	receivedMonths = collectionMonths;
	if (toThrow != null) {
		throw toThrow;
	}
	return result;
}
```

```java
@Test
void 등록_요청의_collectionMonths를_서비스에_전달한다() throws Exception {
	service.result = new BrandRegistrationService.Result(42L, "brandx", 100L, false);

	mvc.perform(post("/api/brands").contentType(MediaType.APPLICATION_JSON)
					.content("{\"username\": \"brandx\", \"collectionMonths\": 3}"))
			.andExpect(status().isCreated());

	assertThat(service.receivedMonths).isEqualTo(3);
}
```

(필드명 `mvc`·`service.result` 주입은 기존 `신규_등록은_201이다` 테스트와 동형 — standalone MockMvc + 실제 ApiExceptionHandler)

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :monitoring:test --tests "com.celfit.monitoring.service.BrandRegistrationServiceTest" --tests "com.celfit.monitoring.web.BrandControllerTest"`
Expected: FAIL — `register(String, String, Integer)` 미정의

- [ ] **Step 3: 구현**

`BrandRegistrationService.java`:

```java
private static final Set<Integer> ALLOWED_MONTHS = Set.of(1, 3, 6, 12);
```

기존 오버로드 위임 갱신 + 정본 메서드:

```java
/** 기존 단일 인자 호출부용 위임 — brandName·collectionMonths 미상은 각자 기본 규칙으로 접는다. */
public Result register(String username) {
	return register(username, null, null);
}

public Result register(String username, String brandName) {
	return register(username, brandName, null);
}

public Result register(String username, String brandName, Integer collectionMonths) {
	if (username == null || username.isBlank()) {
		throw new ValidationException("username은 필수다");
	}
	int months = collectionMonths == null ? 12 : collectionMonths;
	// 검증은 저장 도달 전에 — 값 공간 밖이 내려가면 CHECK 위반이 500으로 샌다(was 400과 이중 방어).
	if (!ALLOWED_MONTHS.contains(months)) {
		throw new ValidationException("collectionMonths는 1|3|6|12만 허용한다");
	}
	String normalized = username.strip();
	var existing = brands.findByUsername(normalized);
	if (existing.isPresent() && existing.get().status() == BrandStatus.ACTIVE) {
		seedHashtagsSafely(existing.get().id(), normalized, brandName);
		expandIfRequested(existing.get(), months);
		return new Result(existing.get().id(), normalized, null, true);
	}
	ProfileInfo profile = hiker.fetchProfile(normalized);
	long id = brands.insertOrReactivate(normalized, profile, months);
	// …이하 기존 그대로(callCounts.add / findByUsername / seedHashtagsSafely / backfill.execute)
}
```

(Task 1의 임시 `12`가 여기서 `months`로 대체된다)

```java
/**
 * 기간 확장(collectionMonths 스펙 §3) — 자산 창보다 클 때만. 창 상향과 last_swept_on 클리어를
 * 한 UPDATE(expandWindow)로 끝내고 백필을 재제출한다. 재제출이 죽어도 last_swept_on null이라
 * 다음 새벽 스윕이 전체 창을 다시 연다(등록 백필과 같은 백스톱 규율). 열거는 최신부터 커서
 * 단방향이라 "새 컷까지 재열거"가 증분 수집의 실체다 — 기지 게시물은 insert 스킵(멱등 upsert).
 * 축소는 무시한다(수집된 사실이 정본 — 요청서 §4).
 */
private void expandIfRequested(BrandRow existing, int months) {
	if (months <= existing.collectionMonths()) {
		return;
	}
	brands.expandWindow(existing.id(), months);
	BrandRow row = brands.findByUsername(existing.username()).orElseThrow();
	backfill.execute(() -> runBackfillSafely(row));
}
```

클래스 javadoc의 replay 서술에 "더 큰 collectionMonths면 기간 확장(expandIfRequested)" 한 줄 추가. `java.util.Set` import 확인.

`BrandController.java`:

```java
/** brandName·collectionMonths는 하위 호환용 nullable — 기존 요청 바디(필드 없음)는 null로 들어온다. */
public record BrandRegisterRequest(String username, String brandName, Integer collectionMonths) {}
```

`register()`: `service.register(req.username(), req.brandName(), req.collectionMonths())`.

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :monitoring:test`
Expected: PASS (모듈 전체 — 등록·수집·스토어 회귀 포함)

- [ ] **Step 5: Commit**

```bash
git add monitoring/src && git commit -m "feat(monitoring): 등록 API collectionMonths 수용 + 더 큰 창 재등록은 기간 확장"
```

---

### Task 4: was — 응답 collectionMonths·collectionStartedAt + 확장 중 collecting 유도

**Files:**
- Modify: `was/src/main/java/com/celfit/was/monitoring/BrandReadRepository.java` (SELECT + `BrandAccountRow`)
- Modify: `was/src/main/java/com/celfit/was/v1/brandmonitoring/BrandAccountResponse.java`
- Modify: `was/src/main/java/com/celfit/was/v1/brandmonitoring/BrandAccountAssembler.java`
- Test: `was/src/test/java/com/celfit/was/v1/brandmonitoring/V1BrandAccountsControllerTest.java`
- Test(기계 갱신): `new BrandAccountRow(` 생성부 전체 — `V1BrandPostsControllerTest.java`, `V1BrandAccountServiceWithdrawalTest.java`, `PerformanceComparisonAssemblerTest.java`, `PerformanceContentAssemblerTest.java`, `V2CampaignContentServiceTest.java` (총 15곳: `grep -rn "new BrandAccountRow" was/src --exclude-dir=docs`)

**Interfaces:**
- Produces: `BrandAccountRow(…, String imageObjectPath, int collectionMonths, OffsetDateTime collectionStartedAt)` — 끝에 2필드 추가
- Produces: `BrandAccountResponse(String id, String accountType, int collectionMonths, Profile profile, String collectionStatus, String collectionStartedAt, …)` — accountType 뒤에 삽입
- Produces: 상태 유도 3분기 — `lastSweptOn != null → ready` / `lastSweptOn == null && backfillCompletedAt != null → collecting`(확장) / 둘 다 null → 현행(lastSweptAt ? ready : error/collecting)

- [ ] **Step 1: 실패하는 테스트 작성** — `V1BrandAccountsControllerTest`에 헬퍼·테스트 추가.

기존 행 헬퍼 4개(collectingRow·sweptFactRow·readyRow·errorRow)의 생성자 끝에 `, 12, OffsetDateTime.parse("2026-08-01T00:00:00Z")` 추가(테스트 의미 불변). 신규 헬퍼:

```java
/** 확장 수집 진행 — 완주 이력(backfill_completed_at)이 있는데 last_swept_on이 비었다. 데이터는 서빙 중. */
private static BrandAccountRow expandingRow(long brandId) {
	return new BrandAccountRow(brandId, "lizda_official", null,
			OffsetDateTime.parse("2026-08-07T00:00:00Z"), OffsetDateTime.parse("2026-08-01T00:00:00Z"),
			OffsetDateTime.parse("2026-08-01T01:00:00Z"), null,
			30876L, 12L, 340L, null, null, "https://cdn/pic.jpg", null, null, "ACTIVE", null,
			6, OffsetDateTime.parse("2026-08-12T10:00:00Z"));
}
```

테스트 2건:

```java
@Test
void 확장_중에는_collecting으로_전이하되_기존_데이터는_그대로_서빙한다() {
	given(linkRepository.findActiveByUserAndBrand(7L, 100L)).willReturn(Optional.of(link(7L, 100L)));
	given(brandReadRepository.findAccount(100L)).willReturn(Optional.of(expandingRow(100L)));

	mockMvc.perform(get("/v1/brand-monitoring/accounts/100").with(user(principal())))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.collectionStatus").value("collecting"))
			.andExpect(jsonPath("$.data.collectionMonths").value(6))
			// 확장 시작 시각(collection_started_at)이 앵커다 — registered_at이 아니다(FE 폴링 30분 상한).
			.andExpect(jsonPath("$.data.collectionStartedAt").value("2026-08-12T19:00:00+09:00"))
			.andExpect(jsonPath("$.data.collectionError").value(Matchers.nullValue()));
}

@Test
void 응답은_자산의_collectionMonths를_그대로_싣는다() {
	given(linkRepository.findActiveByUserAndBrand(7L, 100L)).willReturn(Optional.of(link(7L, 100L)));
	given(brandReadRepository.findAccount(100L)).willReturn(Optional.of(readyRow(100L)));

	mockMvc.perform(get("/v1/brand-monitoring/accounts/100").with(user(principal())))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.collectionStatus").value("ready"))
			.andExpect(jsonPath("$.data.collectionMonths").value(12));
}
```

`V1BrandPostsControllerTest`에 확장 중 서빙 회귀 1건(스펙 테스트 계획 마지막 행 — 게시물 표면은 상태 게이트가 없음을 고정):

```java
@Test
void 확장_수집_중에도_게시물_목록은_정상_서빙된다() throws Exception {
	// 확장 중 = last_swept_on null + 완주 이력 있음(스펙 2026-08-12 §5). FE는 collecting 중에도
	// 기존 데이터 위에 진행 배너만 띄운다(요청서 §4 조건 ①) — 목록이 비면 그 UX가 무너진다.
	given(brandReadRepository.findAccount(100L)).willReturn(Optional.of(
			new BrandAccountRow(100L, "lizda_official", null,
					OffsetDateTime.parse("2026-08-07T18:00:00Z"), OffsetDateTime.parse("2026-08-01T00:00:00Z"),
					OffsetDateTime.parse("2026-08-01T01:00:00Z"), null, 30876L, 12L, 340L, null, "리즈다",
					"https://cdn/pic.jpg", true, null, "ACTIVE", null,
					12, OffsetDateTime.parse("2026-08-12T10:00:00Z"))));
	givenTagged(taggedRow("P001", "2026-08-01T00:00:00Z"));
	given(brandReadRepository.findPostMeta(any())).willReturn(List.of(meta("P001", "REELS", null)));

	mockMvc.perform(get("/v1/brand-monitoring/accounts/100/posts").with(user(principal())))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.length()").value(1));
}
```

- [ ] **Step 2: 컴파일 실패 확인**

Run: `./gradlew :was:compileTestJava`
Expected: FAIL — BrandAccountRow 생성자 인자 수 불일치

- [ ] **Step 3: 구현**

`BrandReadRepository.java`:
- `findAccount` SELECT에 `collection_months,`와 `COALESCE(collection_started_at, registered_at) AS collection_started_at` 추가.
- `BrandAccountRow` record 끝에 `int collectionMonths, OffsetDateTime collectionStartedAt` 추가. javadoc에 "collectionMonths는 자산 레벨 수집 창(공유 유저 간 max — 스펙 2026-08-12), collectionStartedAt은 확장 시 갱신되는 폴링 앵커(기존 행은 registered_at 폴백)" 추가.

`BrandAccountResponse.java` — `accountType` 뒤에 `int collectionMonths` 삽입:

```java
public record BrandAccountResponse(String id, String accountType, int collectionMonths, Profile profile,
		String collectionStatus, String collectionStartedAt, String collectionCompletedAt,
		String lastDetectedAt, String lastTrackedAt, String nextScheduledAt,
		CollectionError collectionError, String createdAt) {
```

(javadoc에 "collectionMonths는 자산 값 그대로 — 3개월 유저도 자산이 12면 12를 본다(스펙 결정 요약)" 한 줄)

`BrandAccountAssembler.java` — `toResponse` 상태 유도·필드 배선 교체:

```java
public BrandAccountResponse toResponse(BrandAccountRow row, String accountType) {
	String status;
	if (row.lastSweptOn() != null) {
		status = STATUS_READY;
	} else if (row.backfillCompletedAt() != null) {
		// 확장/재수집 진행(스펙 2026-08-12 §5) — 완주 이력이 있는데 last_swept_on이 비어 있다 =
		// 창을 다시 여는 중. 데이터는 계속 서빙되고 FE는 "collecting + 게시물 있음 = 확장 배너"로
		// 판정한다. 실패해도 error로 바꾸지 않는다 — 다음 스윕이 백스톱하고, 기존 데이터 위에
		// "초기 수집 실패" 오보를 띄우지 않기 위해서다.
		status = STATUS_COLLECTING;
	} else if (row.lastSweptAt() != null) {
		// 첫 등록 스트리밍 fast-ready(서빙 창 커버) / 재가입 직후 기존 데이터 보유(08-10 결정).
		// backfill_error가 남아 있어도 무시한다 — 데이터가 있는데 에러 화면을 띄우는 오보 방지.
		status = STATUS_READY;
	} else {
		status = row.backfillError() != null ? STATUS_ERROR : STATUS_COLLECTING;
	}
	BrandAccountResponse.CollectionError error = STATUS_ERROR.equals(status)
			? new BrandAccountResponse.CollectionError(BACKFILL_FAILED, row.backfillError())
			: null;

	String sweptAt = KstTimestamps.toKstIso(row.lastSweptAt());

	return new BrandAccountResponse(
			String.valueOf(row.id()),
			accountType,
			row.collectionMonths(),
			profile(row),
			status,
			// 확장 시 monitoring이 collection_started_at을 갱신한다 — FE 폴링 30분 상한의 앵커(요청서 §4).
			KstTimestamps.toKstIso(row.collectionStartedAt()),
			KstTimestamps.toKstIso(row.backfillCompletedAt()),
			sweptAt,
			sweptAt,
			nextScheduledAt(ZonedDateTime.now(KstTimestamps.KST), sweepHourKst),
			error,
			KstTimestamps.toKstIso(row.registeredAt()));
}
```

클래스 javadoc의 상태 유도 표를 3분기 규칙으로 갱신(08-10 근거 서술은 "첫 등록·재가입 분기에 한정 유지"로 정정).

- [ ] **Step 4: 나머지 `new BrandAccountRow(` 생성부 기계 갱신** — `grep -rn "new BrandAccountRow" was/src --exclude-dir=docs`로 전수 확인 후, 각 생성자 끝에 `, 12, <그 행의 registeredAt 인자와 같은 값>` 추가(perf dashboard·campaign 테스트는 값 자체를 검증하지 않으므로 의미 불변).

- [ ] **Step 5: 테스트 통과 확인**

Run: `./gradlew :was:test --tests "com.celfit.was.v1.brandmonitoring.*" --tests "com.celfit.was.v1.perfdashboard.*" --tests "com.celfit.was.v2.monitoring.*"`
Expected: PASS — 신규 2건 + 기존 상태 유도(sweptFactRow는 backfillCompletedAt null이라 여전히 ready) 회귀

- [ ] **Step 6: Commit**

```bash
git add was/src && git commit -m "feat(was): 계정 응답 collectionMonths 추가 + 확장 중 collecting 유도"
```

---

### Task 5: was — POST collectionMonths 검증·전파·확장 게이트

**Files:**
- Create: `was/src/main/java/com/celfit/was/v1/brandmonitoring/BrandCollectionMonths.java`
- Modify: `was/src/main/java/com/celfit/was/monitoring/MonitoringCommandClient.java` (registerBrand 3인자)
- Modify: `was/src/main/java/com/celfit/was/v1/brandmonitoring/V1BrandAccountService.java`
- Modify: `was/src/main/java/com/celfit/was/v1/brandmonitoring/V1BrandAccountsController.java`
- Test: `was/src/test/java/com/celfit/was/v1/brandmonitoring/V1BrandAccountsControllerTest.java`
- Test: `was/src/test/java/com/celfit/was/monitoring/MonitoringBrandCommandClientTest.java`
- Test(기계 갱신): 기존 `registerBrand(` 스텁·검증 전부 3인자화 — `V1BrandAccountsControllerTest.java`(다수), `V1BrandAccountServiceWithdrawalTest.java`

**Interfaces:**
- Consumes: `BrandAccountRow.collectionMonths()` (Task 4), monitoring 확장 replay (Task 3)
- Produces: `BrandCollectionMonths.DEFAULT`(=12) / `orDefault(Integer)` / `isValid(int)`
- Produces: `MonitoringCommandClient.registerBrand(String username, String brandName, int collectionMonths)`
- Produces: `V1BrandAccountService.register(long userId, String rawUsername, String rawAccountType, Integer rawCollectionMonths)`
- Produces: `BrandAccountRegisterRequest(String username, String accountType, Integer collectionMonths)`

- [ ] **Step 1: 실패하는 테스트 작성**

`MonitoringBrandCommandClientTest.브랜드_등록_요청과_응답_파싱`에 `jsonPath("$.collectionMonths").value(3)` 기대 추가 + 호출을 `client.registerBrand("brand_official", "브랜드코퍼레이션", 3)`으로. 나머지 registerBrand 호출부는 `, 12` 추가.

`V1BrandAccountsControllerTest`에 3건:

```java
@Test
void 값_공간_밖_collectionMonths는_400이다() throws Exception {
	mockMvc.perform(post("/v1/brand-monitoring/accounts").with(user(principal())).with(csrf())
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"username\": \"lizda_official\", \"collectionMonths\": 2}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

	then(commandClient).should(never()).registerBrand(anyString(), any(), org.mockito.ArgumentMatchers.anyInt());
}

@Test
void 신규_등록은_collectionMonths를_monitoring에_전달한다() throws Exception {
	given(commandClient.registerBrand("lizda_official", null, 3))
			.willReturn(new MonitoringCommandClient.BrandRegisterResult(100L, "lizda_official", 30876L, "ACTIVE"));
	given(brandReadRepository.findAccount(100L)).willReturn(Optional.of(collectingRow(100L, "lizda_official")));
	given(linkRepository.findActiveByUserAndBrand(7L, 100L)).willReturn(Optional.of(link(7L, 100L)));

	mockMvc.perform(post("/v1/brand-monitoring/accounts").with(user(principal())).with(csrf())
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"username\": \"lizda_official\", \"collectionMonths\": 3}"))
			.andExpect(status().isAccepted());

	then(commandClient).should().registerBrand("lizda_official", null, 3);
}

@Test
void 이미_연결된_계정의_더_큰_창_재등록은_확장으로_monitoring을_재호출한다() throws Exception {
	// 자산 창 12(readyRow 헬퍼) — 같은 12 재요청은 게이트가 접고, 더 큰 값이 없으므로
	// collectingRow 기반 6개월 자산으로 만들어 12 요청이 확장을 태우게 한다.
	given(linkRepository.findAllActiveByUser(7L)).willReturn(List.of(link(7L, 100L)));
	given(brandReadRepository.findAccount(100L)).willReturn(Optional.of(expandingRowMonths(100L, 6)));
	given(linkRepository.findActiveByUserAndBrand(7L, 100L)).willReturn(Optional.of(link(7L, 100L)));
	given(commandClient.registerBrand("lizda_official", null, 12))
			.willReturn(new MonitoringCommandClient.BrandRegisterResult(100L, "lizda_official", 30876L, "ACTIVE"));

	mockMvc.perform(post("/v1/brand-monitoring/accounts").with(user(principal())).with(csrf())
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"username\": \"lizda_official\", \"collectionMonths\": 12}"))
			.andExpect(status().isAccepted());

	then(commandClient).should().registerBrand("lizda_official", null, 12);
	then(linkRepository).should(never()).insertLink(anyLong(), anyLong(), anyString(), anyString());
}

@Test
void 이미_연결된_계정의_같거나_작은_창_재등록은_monitoring_호출이_없다() throws Exception {
	given(linkRepository.findAllActiveByUser(7L)).willReturn(List.of(link(7L, 100L)));
	given(brandReadRepository.findAccount(100L)).willReturn(Optional.of(readyRow(100L)));   // 자산 12
	given(linkRepository.findActiveByUserAndBrand(7L, 100L)).willReturn(Optional.of(link(7L, 100L)));

	mockMvc.perform(post("/v1/brand-monitoring/accounts").with(user(principal())).with(csrf())
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"username\": \"lizda_official\", \"collectionMonths\": 3}"))
			.andExpect(status().isAccepted())
			.andExpect(jsonPath("$.data.collectionMonths").value(12));   // 축소 없음 — 자산 값 유지

	then(commandClient).should(never()).registerBrand(anyString(), any(), org.mockito.ArgumentMatchers.anyInt());
}
```

헬퍼 추가(Task 4의 expandingRow에 months 파라미터판):

```java
private static BrandAccountRow expandingRowMonths(long brandId, int months) {
	return new BrandAccountRow(brandId, "lizda_official", LocalDate.of(2026, 8, 7),
			OffsetDateTime.parse("2026-08-07T00:00:00Z"), OffsetDateTime.parse("2026-08-01T00:00:00Z"),
			OffsetDateTime.parse("2026-08-01T01:00:00Z"), null,
			30876L, 12L, 340L, null, null, "https://cdn/pic.jpg", null, null, "ACTIVE", null,
			months, OffsetDateTime.parse("2026-08-01T00:00:00Z"));
}
```

기존 registerBrand 스텁·검증 전부 3인자화: `registerBrand("lizda_official", null)` → `registerBrand("lizda_official", null, 12)`, `registerBrand("lizda_official", "끌리메")` → `("lizda_official", "끌리메", 12)` 등(then 검증 포함, `V1BrandAccountServiceWithdrawalTest`도 grep로 확인).

- [ ] **Step 2: 컴파일 실패 확인**

Run: `./gradlew :was:compileTestJava`
Expected: FAIL — `registerBrand(String, String, int)` 미정의

- [ ] **Step 3: 구현**

`BrandCollectionMonths.java` (BrandAccountType 동형 상수 클래스):

```java
package com.celfit.was.v1.brandmonitoring;

import java.util.Set;

/**
 * 브랜드 수집 범위 값 공간(FE 요청서 2026-08-12) — 등록 요청 검증·기본값의 단일 정의.
 * 값은 자산 레벨(monitoring brand_account.collection_months)로 저장되고 절대 줄지 않는다
 * (공유 유저 간 max — 스펙 결정 요약). CHECK 제약과 monitoring 검증이 같은 집합을 이중 방어한다.
 */
public final class BrandCollectionMonths {

	public static final int DEFAULT = 12;

	private static final Set<Integer> ALLOWED = Set.of(1, 3, 6, 12);

	private BrandCollectionMonths() {
	}

	/** null은 12로 접는다(하위 호환 — collectionMonths 없는 기존 요청 본문은 현행 12개월 그대로). */
	public static int orDefault(Integer months) {
		return months == null ? DEFAULT : months;
	}

	public static boolean isValid(int months) {
		return ALLOWED.contains(months);
	}
}
```

`MonitoringCommandClient.java`:

```java
public BrandRegisterResult registerBrand(String username, String brandName, int collectionMonths) {
	return exchange(() -> restClient.post().uri("/api/brands")
			.body(new BrandRegisterRequest(username, brandName, collectionMonths))
			.retrieve().body(BrandRegisterResult.class));
}

record BrandRegisterRequest(String username, String brandName, int collectionMonths) {
}
```

(registerBrand javadoc에 "collectionMonths는 수집 창(1|3|6|12) — 이미 활성인 브랜드에 더 큰 값이면 monitoring이 기간 확장으로 처리한다" 한 줄)

`V1BrandAccountService.register` — 시그니처·검증·확장 게이트:

```java
public BrandAccountResponse register(long userId, String rawUsername, String rawAccountType,
		Integer rawCollectionMonths) {
	String username = BrandUsername.normalize(rawUsername);
	BrandUsername.validate(username);
	String accountType = BrandAccountType.orDefault(rawAccountType);
	// 검증은 반드시 리포지토리 도달 전에 — 잘못된 값이 그대로 내려가면 CHECK 제약 위반이 500으로 샌다.
	if (!BrandAccountType.isValid(accountType)) {
		throw V1ApiException.validation("accountType 값이 올바르지 않아요.");
	}
	int months = BrandCollectionMonths.orDefault(rawCollectionMonths);
	if (!BrandCollectionMonths.isValid(months)) {
		throw V1ApiException.validation("collectionMonths 값이 올바르지 않아요.");
	}
	Optional<Long> alreadyLinked = linkTransaction.precheck(userId, username, accountType);
	if (alreadyLinked.isPresent()) {
		long brandId = alreadyLinked.get();
		// 기간 확장(스펙 §3) — 자산 창보다 클 때만 monitoring 재호출. 사전 게이트일 뿐 정본 판정은
		// monitoring replay가 한 번 더 한다(경합으로 게이트가 낡아도 결과는 같다). 같거나 작은 값은
		// 현행 멱등 경로 그대로 monitoring 콜 0이다(축소 없음 — 수집된 사실이 정본).
		if (months > findAccountOrThrow(brandId).collectionMonths()) {
			String brandName = BrandAccountType.OWN.equals(accountType) ? brandNameOf(userId) : null;
			translate(() -> commandClient.registerBrand(username, brandName, months));
		}
		return get(userId, brandId);
	}

	String brandName = BrandAccountType.OWN.equals(accountType) ? brandNameOf(userId) : null;
	BrandRegisterResult registered = translate(() -> commandClient.registerBrand(username, brandName, months));
	// …이하 기존 그대로(link/compensate/get)
}
```

`V1BrandAccountsController.java`:

```java
/** 등록 요청 본문 — 계정명·타입(생략 시 own)·수집 창(생략 시 12, 값 공간은 BrandCollectionMonths). */
public record BrandAccountRegisterRequest(String username, String accountType, Integer collectionMonths) {
}
```

`register()`에서 `Integer collectionMonths = body == null ? null : body.collectionMonths();`를 뽑아 `service.register(principal.getUserId(), username, accountType, collectionMonths)`로 전달.

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :was:test --tests "com.celfit.was.v1.brandmonitoring.*" --tests "com.celfit.was.monitoring.*"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add was/src && git commit -m "feat(was): 등록 collectionMonths 검증·전파 + 기간 확장 사전 게이트"
```

---

### Task 6: nextScheduledAt 표기 정정(02:00 수용) + 크론 드리프트 레포 정렬

**Files:**
- Modify: `was/src/main/java/com/celfit/was/v1/brandmonitoring/BrandAccountAssembler.java:37` (기본값 3→2)
- Modify: `deploy/compose.yaml:300-302` (브랜드 스윕 크론 18:00→17:00 UTC + 주석)
- Modify: `monitoring/src/main/resources/application.yml:51` (주석 정정)
- Test: `was/src/test/java/com/celfit/was/v1/brandmonitoring/BrandAccountAssemblerTest.java` (정적 메서드 테스트는 hourKst 명시 인자라 불변 — 회귀만 확인)

**Interfaces:**
- Produces: `was.brand.sweep-hour-kst` 기본 2 — 응답 `nextScheduledAt`이 KST 02:00 기준으로 계산됨

- [ ] **Step 1: 구현** (표기 기본값·설정 정렬 — 로직 불변이라 신규 테스트 없음, 기존 경계 테스트가 회귀 가드)

`BrandAccountAssembler.java:37`: `@Value("${was.brand.sweep-hour-kst:3}")` → `@Value("${was.brand.sweep-hour-kst:2}")`. `nextScheduledAt` javadoc에 "08-12 정정: 운영 브랜드 스윕은 서버 크론 KST 02:00(캠페인 스윕과 동시 — 사용자 수용)이라 기본값을 2로 맞춘다" 추가.

`deploy/compose.yaml` 브랜드 스윕 크론 블록:

```yaml
      # 브랜드 태그 스윕 — KST 02:00(UTC 17:00). 설계 원안은 03:00(캠페인 스윕과 시차)이었으나
      # 서버 override가 02:00으로 운영돼 왔고 08-12에 02:00을 정본으로 수용(캠페인 스윕과 동시 실행).
      # was.brand.sweep-hour-kst(nextScheduledAt 표기)와 반드시 함께 움직일 것.
      MONITORING_BRAND_SCHEDULE_SWEEP_CRON: "0 0 17 * * *"
```

`monitoring/src/main/resources/application.yml:51` 주석: `# "-"=비활성. 운영은 KST 02:00(UTC 17:00) env 주입 — 08-12 서버 드리프트를 정본으로 수용`.

`V1BrandAccountsControllerTest`의 `응답은_자산의_collectionMonths를_그대로_싣는다`(Task 4)에 표기 검증 추가 — 날짜부는 실행일에 따라 변하므로 시각 접미사만 고정:

```java
.andExpect(jsonPath("$.data.nextScheduledAt").value(Matchers.endsWith("T02:00:00+09:00")))
```

- [ ] **Step 2: 회귀 확인**

Run: `./gradlew :was:test --tests "com.celfit.was.v1.brandmonitoring.BrandAccountAssemblerTest" --tests "com.celfit.was.v1.brandmonitoring.V1BrandAccountsControllerTest"`
Expected: PASS (정적 메서드 테스트는 hourKst를 인자로 받아 기본값 무관, 컨트롤러 슬라이스는 @Value 기본값 2를 태운다)

- [ ] **Step 3: Commit**

```bash
git add was/src deploy/compose.yaml monitoring/src/main/resources/application.yml
git commit -m "fix(was): nextScheduledAt 표기를 실제 브랜드 스윕 시각(KST 02:00)으로 정정"
```

---

### Task 7: 전체 검증 · 문서 · PR

**Files:**
- Modify: `DECISIONS.md` (맨 위에 신규 결정)
- Move: `docs/superpowers/plans/2026-08-12-brand-collection-months.md` → `docs/superpowers/plans/archive/`
- Modify: `docs/superpowers/specs/2026-08-12-brand-collection-months-design.md` (상태 헤더 → `✅ 구현됨`)

- [ ] **Step 1: 모듈 테스트 전체**

Run: `./gradlew :monitoring:test :was:test`
Expected: PASS 전건

- [ ] **Step 2: 마이그레이션 가드 확인**

Run: `bash check-migration-safety.sh` (리포 루트 스크립트 — 경로가 다르면 `git grep -l check-migration-safety`로 위치 확인)
Expected: 신규 마이그레이션은 ADD COLUMN/CHECK/UPDATE 백필뿐이라 통과

- [ ] **Step 3: DECISIONS.md 맨 위에 결정 추가**

```markdown
## 2026-08-12 브랜드 수집 범위(collectionMonths)는 자산 레벨 max — 확장은 last_swept_on 클리어로 백스톱 상속

수집 창(1/3/6/12개월)은 공유 크롤 자산 `brand_account.collection_months` 하나로 관리하고 절대
줄이지 않는다(유저 간 max — 3개월 유저가 12개월치를 보는 쪽은 무해). 더 큰 값 재등록 = 기간
확장이며, 구현은 `last_swept_on` 클리어 + 백필 재제출이 전부다 — 실패해도 다음 새벽 스윕이
전체 창을 다시 여는 기존 백스톱을 그대로 상속한다. 확장 중 상태는 was 유도 규칙의 신설 분기
(`last_swept_on null && backfill_completed_at 있음 → collecting`)로 표현하고 데이터는 계속
서빙한다. 부수 확정: 브랜드 스윕 크론은 서버 드리프트값 KST 02:00을 정본으로 수용(레포
compose 정렬 + nextScheduledAt 표기 기본값 2). 스펙:
docs/superpowers/specs/2026-08-12-brand-collection-months-design.md
```

- [ ] **Step 4: 스펙 상태 갱신 + plan 아카이브 + 커밋**

```bash
git mv docs/superpowers/plans/2026-08-12-brand-collection-months.md docs/superpowers/plans/archive/
# 스펙 상태 헤더를 "> 상태: 🟢 활성 · ✅ 구현됨"으로 수정 후
git add -A && git commit -m "docs: collectionMonths 결정 기록 + plan 아카이브"
```

- [ ] **Step 5: PR 생성** (develop 대상)

```bash
git push -u origin feature/brand-account-collection-months-a97816
gh pr create --base develop --title "feat: 브랜드 계정 수집 범위 선택(collectionMonths) + 기간 확장" --body "..."
```

PR 본문에 포함: 스펙 링크, FE 회신 포인트 3개(02:00 정정·collectionCompletedAt 유지·자산 레벨 max), 배포 주의(was가 monitoring DB 신규 컬럼을 읽으므로 monitoring 마이그레이션이 먼저 적용돼야 함 — 08-07 brand_was_contract_fields와 같은 동시 배포 관용, 롤링 수 초 창 수용).
