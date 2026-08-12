# 경쟁사 모니터링 계정 타입(accountType) 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 브랜드 구독(`app.brand_monitorings`)에 `own`/`competitor` 타입을 저장하고, 등록·조회·상한·성과 대시보드·캠페인 연결까지 그 타입을 관통시킨다.

**Architecture:** 타입은 계정이 아니라 **유저-계정 관계**의 속성이다. 관계 테이블에 컬럼 하나(`account_type`)를 추가하고, 그 값을 읽는 지점 5곳(계정 API 응답·상한 판정·`/contents` 필터·`comparison` 필드·캠페인 연결 방어)을 잇는다. 신규 테이블·재수집·monitoring 모듈 변경은 없다.

**Tech Stack:** Java 21, Spring Boot 4.1, JdbcClient, Flyway(was `app` 스키마), JUnit 5 + Mockito + `@WebMvcTest` 슬라이스.

**설계 문서:** [specs/2026-08-12-competitor-account-type-design.md](../specs/2026-08-12-competitor-account-type-design.md)
**FE 회신:** 문서로 남기지 않고 채팅으로 전달했다(08-12). 전달 내용의 근거는 설계 문서 §요청서와 다른 점에 있다.

## Global Constraints

- 주석·로그·커밋 메시지는 **한국어**. 커밋 prefix는 `feat(was):` / `test(was):` / `docs:`.
- 값 공간은 정확히 `"own"` | `"competitor"` 두 개. 기본값은 `"own"`.
- 상한: **own 6, competitor 3**. 초과는 **409** — own `BRAND_ACCOUNT_LIMIT_REACHED`, competitor `COMPETITOR_ACCOUNT_LIMIT_REACHED`.
- 값 공간 밖 `accountType` 입력은 **400 `VALIDATION_FAILED`**.
- 신규 Flyway 마이그레이션은 UTC 타임스탬프 채번. **기존 `V1`~`V9` 파일은 rename 금지.**
- expand-contract: 이번 릴리스에 `DROP`·`RENAME`·타입 변경·`SET NOT NULL` 없음.
- **이 머신의 도커는 Docker Desktop이다**(08-12 확인 — colima 미설치). `DOCKER_HOST`를 **설정하지 않는 것**이 정답이다. CLAUDE.md의 colima 소켓 export 지침은 이 머신에 해당하지 않으니 따르지 말 것 — 그 값을 넣으면 Testcontainers가 소켓을 못 찾아 통합 테스트가 대량 실패한다.
- 테스트는 모듈 단위로: `./gradlew :was:test --tests "com.celfit.was.v1.brandmonitoring.*"`. 전체 `./gradlew test`는 PR 직전에만.

## File Structure

| 파일 | 책임 | 변경 |
|---|---|---|
| `was/src/main/resources/db/migration/app/V20260811164500__brand_monitorings_account_type.sql` | 컬럼·CHECK 추가 | 신규 |
| `was/src/main/java/com/celfit/was/monitoring/BrandLinkRow.java` | 링크 1행 | `accountType` 필드 추가 |
| `was/src/main/java/com/celfit/was/monitoring/BrandLinkRepository.java` | 링크 저장 계층 | `account_type` SELECT·INSERT·UPDATE |
| `was/src/main/java/com/celfit/was/v1/brandmonitoring/BrandAccountType.java` | 값 공간·상한·에러 코드의 단일 정의 | 신규 |
| `was/src/main/java/com/celfit/was/v1/brandmonitoring/BrandLinkTransaction.java` | 트랜잭션 경계·상한 강제 | 타입별 상한, `changeType` |
| `was/src/main/java/com/celfit/was/v1/brandmonitoring/BrandAccountResponse.java` | 계정 응답 셰이프 | `accountType` 필드 |
| `was/src/main/java/com/celfit/was/v1/brandmonitoring/BrandAccountAssembler.java` | 브랜드 행 → 응답 | 시그니처에 타입 인자 |
| `was/src/main/java/com/celfit/was/v1/brandmonitoring/V1BrandAccountService.java` | 계정 라이프사이클 | 등록 타입·타입 변경·조회 |
| `was/src/main/java/com/celfit/was/v1/brandmonitoring/V1BrandAccountsController.java` | HTTP 표면 | 요청 본문·PATCH·meta |
| `was/src/main/java/com/celfit/was/v1/perfdashboard/PerformanceContentAssembler.java` | 콘텐츠 조립 | 경쟁사 brandId 집합 노출 |
| `was/src/main/java/com/celfit/was/v1/perfdashboard/V1PerformanceDashboardController.java` | 대시보드 표면 | `accountType` 필터 |
| `was/src/main/java/com/celfit/was/v1/perfdashboard/PerformanceComparisonResponse.java` | 비교 응답 | `accountType` 필드 |
| `was/src/main/java/com/celfit/was/v1/perfdashboard/PerformanceComparisonAssembler.java` | 비교 조립 | 타입 관통 |
| `was/src/main/java/com/celfit/was/v2/monitoring/V2CampaignContentService.java` | 캠페인 콘텐츠 관계 | 경쟁사 게시물 거절 |

`BrandAccountType`을 새로 만드는 이유: 값 공간·상한·에러 코드가 5개 파일에 흩어지면 own/competitor 문자열이 곳곳에 하드코딩된다. 한 곳에서만 정의하고 나머지는 참조한다.

---

### Task 1: 마이그레이션 + 링크 행에 타입 싣기

**Files:**
- Create: `was/src/main/resources/db/migration/app/V20260811164500__brand_monitorings_account_type.sql`
- Create: `was/src/main/java/com/celfit/was/v1/brandmonitoring/BrandAccountType.java`
- Modify: `was/src/main/java/com/celfit/was/monitoring/BrandLinkRow.java`
- Modify: `was/src/main/java/com/celfit/was/monitoring/BrandLinkRepository.java`
- Test: `was/src/test/java/com/celfit/was/monitoring/BrandLinkRepositoryTest.java` (기존 파일이 없으면 신규)

**Interfaces:**
- Produces: `BrandAccountType.OWN`("own") / `COMPETITOR`("competitor") / `isValid(String)` / `ownLimit()`=6 / `competitorLimit()`=3 / `limitOf(String)` / `limitCodeOf(String)`
- Produces: `BrandLinkRow(long id, long userId, long brandId, String username, String accountType, OffsetDateTime createdAt, OffsetDateTime deletedAt)` — `accountType`이 `username` 뒤, `createdAt` 앞
- Produces: `BrandLinkRepository.insertLink(long userId, long brandId, String username, String accountType)` / `updateAccountType(long userId, long brandId, String accountType)` → `boolean`

- [ ] **Step 1: 마이그레이션 파일 작성**

`was/src/main/resources/db/migration/app/V20260811164500__brand_monitorings_account_type.sql`:

```sql
-- 경쟁사 모니터링 계정 타입(2026-08-11 FE 요청) — 타입은 계정이 아니라 유저-계정 관계의 속성이다.
-- 같은 인스타 계정이 유저마다 다른 타입일 수 있어(담당 브랜드 vs 경쟁사) 구독 테이블에 둔다.
-- 기존 등록분은 전부 own이다 — 경쟁사 지정은 지금까지 브라우저 localStorage에만 있었으므로
-- 서버에 없는 게 맞고, 배포 후 유저가 경쟁사 화면에서 다시 지정한다(FE 안내).
-- DEFAULT가 백필을 대신하므로 보정 UPDATE를 동봉하지 않는다.
-- 롤링 안전: 신규 컬럼 + DEFAULT라 구버전 코드의 INSERT(user_id, brand_id, username)에 기본값이 먹는다.
ALTER TABLE app.brand_monitorings
    ADD COLUMN account_type text NOT NULL DEFAULT 'own';

-- 값 공간은 정확히 둘. 애플리케이션 검증(BrandAccountType)의 최후 보루다.
ALTER TABLE app.brand_monitorings
    ADD CONSTRAINT brand_monitorings_account_type_chk
    CHECK (account_type IN ('own', 'competitor'));
```

- [ ] **Step 2: 값 공간 정의 클래스 작성**

`was/src/main/java/com/celfit/was/v1/brandmonitoring/BrandAccountType.java`:

```java
package com.celfit.was.v1.brandmonitoring;

/**
 * 브랜드 구독 타입(2026-08-12 FE 요청) — 값 공간·타입별 상한·상한 초과 에러 코드의 단일 정의.
 * 타입은 계정이 아니라 유저-계정 관계의 속성이라 app.brand_monitorings에 저장된다
 * (같은 인스타 계정이 담당자에게는 own, 경쟁 브랜드 담당자에게는 competitor다).
 *
 * <p>enum이 아니라 상수+검증인 이유: 저장·응답·쿼리 파라미터가 전부 소문자 문자열이고
 * (JdbcClient·Jackson·normalizeFilter), 값 공간이 둘뿐이라 변환 계층이 순비용이다.
 */
public final class BrandAccountType {

	public static final String OWN = "own";
	public static final String COMPETITOR = "competitor";

	/** 내 브랜드 상한(FE 요청서 §2-4). */
	private static final int OWN_LIMIT = 6;
	/** 경쟁사 상한(FE 요청서 §2-4) — own보다 낮다. */
	private static final int COMPETITOR_LIMIT = 3;

	private BrandAccountType() {
	}

	public static boolean isValid(String type) {
		return OWN.equals(type) || COMPETITOR.equals(type);
	}

	/** null·빈 값은 own으로 접는다(하위 호환 — accountType 없는 기존 요청 본문). */
	public static String orDefault(String type) {
		return type == null || type.isBlank() ? OWN : type;
	}

	public static int limitOf(String type) {
		return COMPETITOR.equals(type) ? COMPETITOR_LIMIT : OWN_LIMIT;
	}

	/**
	 * 상한 초과 에러 코드 — own은 기존 코드를 그대로 쓴다(FE가 이미 그 문자열로 분기 중이라
	 * 바꾸면 배포된 등록 화면이 깨진다). competitor만 신설이다.
	 */
	public static String limitCodeOf(String type) {
		return COMPETITOR.equals(type) ? "COMPETITOR_ACCOUNT_LIMIT_REACHED" : "BRAND_ACCOUNT_LIMIT_REACHED";
	}

	/** 상한 초과 메시지 — 타입별 상한 값을 그대로 노출한다(FE가 안내 문구를 다시 만들지 않게). */
	public static String limitMessageOf(String type) {
		return COMPETITOR.equals(type)
				? "경쟁사는 최대 " + COMPETITOR_LIMIT + "개까지 등록할 수 있어요."
				: "내 브랜드는 최대 " + OWN_LIMIT + "개까지 등록할 수 있어요.";
	}

	public static int ownLimit() {
		return OWN_LIMIT;
	}

	public static int competitorLimit() {
		return COMPETITOR_LIMIT;
	}
}
```

- [ ] **Step 3: BrandLinkRow에 필드 추가**

`BrandLinkRow.java`의 record 선언을 바꾸고 javadoc 한 줄을 더한다:

```java
/**
 * app.brand_monitorings 1행 — user↔브랜드 연결(2026-08-07 스펙 §3-1). brandId는 monitoring
 * brand_account.id 논리 참조(크로스 DB FK 없음). deletedAt이 채워진 행은 해제된 과거 연결이고,
 * 활성 조회(findActive*)는 항상 deletedAt IS NULL만 돌려준다.
 *
 * <p>{@code accountType}은 이 관계의 속성이다(own/competitor, 08-12) — 같은 브랜드라도 유저마다
 * 다를 수 있어 brand_account가 아니라 여기 있다. 값 공간은 {@code BrandAccountType}.
 */
public record BrandLinkRow(long id, long userId, long brandId, String username, String accountType,
		OffsetDateTime createdAt, OffsetDateTime deletedAt) {
}
```

- [ ] **Step 4: 저장 계층에 컬럼 관통**

`BrandLinkRepository.java`에서 상수와 두 메서드를 고치고 하나를 추가한다:

```java
	private static final String SELECT_COLUMNS =
			"id, user_id, brand_id, username, account_type, created_at, deleted_at";
```

```java
	/** 활성 연결 생성. RETURNING id. 같은 (유저, 브랜드) 활성 연결이 있으면 DuplicateKeyException. */
	public long insertLink(long userId, long brandId, String username, String accountType) {
		return jdbcClient.sql("""
				INSERT INTO app.brand_monitorings (user_id, brand_id, username, account_type)
				VALUES (:userId, :brandId, :username, :accountType)
				RETURNING id
				""")
				.param("userId", userId)
				.param("brandId", brandId)
				.param("username", username)
				.param("accountType", accountType)
				.query(Long.class)
				.single();
	}

	/**
	 * 활성 연결의 타입 변경(08-12) — 재수집이 아니라 관계 속성만 바꾼다. 이미 그 타입이거나
	 * 활성 연결이 없으면 false(호출부의 멱등·소유권 판정 지점).
	 */
	public boolean updateAccountType(long userId, long brandId, String accountType) {
		return jdbcClient.sql("""
				UPDATE app.brand_monitorings SET account_type = :accountType
				WHERE user_id = :userId AND brand_id = :brandId AND deleted_at IS NULL
				""")
				.param("userId", userId)
				.param("brandId", brandId)
				.param("accountType", accountType)
				.update() > 0;
	}
```

- [ ] **Step 5: 컴파일해서 호출부 깨진 곳을 전부 찾는다**

Run: `./gradlew :was:compileJava`
Expected: FAIL — `insertLink` 인자 개수 불일치(`BrandLinkTransaction`), `BrandLinkRow` 생성자 인자 개수 불일치(테스트). 이 목록이 Task 2 이후의 작업 범위다.

- [ ] **Step 6: 임시로 컴파일만 통과시킨다**

`BrandLinkTransaction.link()`의 호출을 `linkRepository.insertLink(userId, brandId, username, BrandAccountType.OWN)`으로 바꾼다(타입 결정은 Task 2에서 제대로 한다).

Run: `./gradlew :was:compileJava`
Expected: PASS

- [ ] **Step 7: 마이그레이션이 실제로 도는지 확인**

Run: `./gradlew :was:test --tests "com.celfit.was.*FlywayTest" --tests "com.celfit.was.*MigrationTest"`
해당 테스트가 없으면 대신 Flyway를 태우는 통합 테스트 아무거나 하나:
Run: `./gradlew :was:test --tests "com.celfit.was.v1.saved.*"`
Expected: PASS — Testcontainers Postgres에 새 마이그레이션이 적용된다. 실패하면 SQL 문법·스키마명(`app.`)을 확인한다.

- [ ] **Step 8: 마이그레이션 가드 확인**

Run: `.github/scripts/check-migration-safety.sh`
Expected: 신규 파일이 차단 목록에 걸리지 않는다(DROP·RENAME·SET NOT NULL 없음).

- [ ] **Step 9: 커밋**

```bash
git add was/src/main/resources/db/migration/app/V20260811164500__brand_monitorings_account_type.sql \
        was/src/main/java/com/celfit/was/v1/brandmonitoring/BrandAccountType.java \
        was/src/main/java/com/celfit/was/monitoring/BrandLinkRow.java \
        was/src/main/java/com/celfit/was/monitoring/BrandLinkRepository.java \
        was/src/main/java/com/celfit/was/v1/brandmonitoring/BrandLinkTransaction.java
git commit -m "feat(was): 브랜드 구독에 account_type 컬럼 추가(own 기본)"
```

---

### Task 2: 타입별 상한 + 타입 변경 트랜잭션

**Files:**
- Modify: `was/src/main/java/com/celfit/was/v1/brandmonitoring/BrandLinkTransaction.java`
- Test: `was/src/test/java/com/celfit/was/v1/brandmonitoring/V1BrandAccountsControllerTest.java`

**Interfaces:**
- Consumes: `BrandAccountType.*`, `BrandLinkRepository.insertLink(..., accountType)`, `updateAccountType(...)`
- Produces: `BrandLinkTransaction.precheck(long userId, String username, String accountType)` → `Optional<Long>` (이미 연결된 브랜드면 그 brandId — 타입이 다르면 이 안에서 변경까지 끝낸다)
- Produces: `BrandLinkTransaction.link(long userId, long brandId, String username, String accountType)`
- Produces: `BrandLinkTransaction.changeType(long userId, long brandId, String accountType)` → `void` (소유권 없으면 403, 상한 초과면 409)
- Produces: `BrandLinkTransaction.ACCOUNT_LIMIT` 제거 — 컨트롤러 `meta`는 `BrandAccountType.ownLimit()`·`competitorLimit()`를 직접 쓴다

- [ ] **Step 1: 실패하는 테스트를 쓴다 — own 상한 6**

기존 테스트의 헬퍼가 타입을 받도록 먼저 고친다(`V1BrandAccountsControllerTest`):

```java
	private static BrandLinkRow link(long userId, long brandId) {
		return link(userId, brandId, "brand_" + brandId, BrandAccountType.OWN);
	}

	private static BrandLinkRow link(long userId, long brandId, String username) {
		return link(userId, brandId, username, BrandAccountType.OWN);
	}

	private static BrandLinkRow link(long userId, long brandId, String username, String accountType) {
		return new BrandLinkRow(brandId, userId, brandId, username, accountType,
				OffsetDateTime.parse("2026-08-01T00:00:00Z"), null);
	}

	/** 한도 검증용 — 서로 다른 브랜드 n개에 연결된 상태(요청 계정명과 겹치지 않는 이름). */
	private static List<BrandLinkRow> links(int count, String accountType) {
		List<BrandLinkRow> links = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			links.add(link(7L, 200L + i, "other_brand_" + i, accountType));
		}
		return links;
	}
```

기존 `한도_10개면_409...` 테스트를 다음 두 개로 교체한다:

```java
	@Test
	void own이_6개면_409_BRAND_ACCOUNT_LIMIT_REACHED이고_monitoring을_호출하지_않는다() throws Exception {
		given(linkRepository.findAllActiveByUser(7L)).willReturn(links(6, BrandAccountType.OWN));

		mockMvc.perform(post("/v1/brand-monitoring/accounts").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"username\":\"new_brand\"}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("BRAND_ACCOUNT_LIMIT_REACHED"));

		then(commandClient).should(never()).registerBrand(anyString());
	}

	@Test
	void competitor가_3개면_409_COMPETITOR_ACCOUNT_LIMIT_REACHED다() throws Exception {
		given(linkRepository.findAllActiveByUser(7L)).willReturn(links(3, BrandAccountType.COMPETITOR));

		mockMvc.perform(post("/v1/brand-monitoring/accounts").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"username\":\"new_brand\",\"accountType\":\"competitor\"}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("COMPETITOR_ACCOUNT_LIMIT_REACHED"));

		then(commandClient).should(never()).registerBrand(anyString());
	}

	@Test
	void own_6개가_차도_competitor는_등록된다() throws Exception {
		given(linkRepository.findAllActiveByUser(7L)).willReturn(links(6, BrandAccountType.OWN));
		given(commandClient.registerBrand("rival_brand"))
				.willReturn(new MonitoringCommandClient.BrandRegisterResult(300L, "ACTIVE"));
		given(brandReadRepository.findAccount(300L)).willReturn(Optional.of(readyRow(300L)));
		given(linkRepository.findActiveByUserAndBrand(7L, 300L))
				.willReturn(Optional.of(link(7L, 300L, "rival_brand", BrandAccountType.COMPETITOR)));

		mockMvc.perform(post("/v1/brand-monitoring/accounts").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"username\":\"rival_brand\",\"accountType\":\"competitor\"}"))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.data.accountType").value("competitor"));
	}
```

- [ ] **Step 2: 테스트가 실패하는지 확인**

Run: `./gradlew :was:test --tests "com.celfit.was.v1.brandmonitoring.V1BrandAccountsControllerTest"`
Expected: FAIL — 컴파일 에러(`BrandAccountType` 미참조 메서드, `accountType` 응답 필드 없음) 또는 상한 10 기준으로 409가 안 난다.

- [ ] **Step 3: BrandLinkTransaction을 타입별로 고친다**

`ACCOUNT_LIMIT` 상수와 `limitReached()`를 지우고 다음으로 바꾼다:

```java
	/**
	 * monitoring 호출 전 빠른 판정(§5-1 2단계) — 같은 계정명이 이미 연결돼 있으면 그 brandId를
	 * 돌려준다(멱등 경로 — monitoring 호출 자체를 생략). 타입이 다르면 <b>그 자리에서 타입만
	 * 바꾼다</b>(08-12): FE UX가 "이미 등록된 계정을 다시 넣으면 경쟁사로 옮겨진다"라 409가 아니다.
	 * 대상 타입 한도 초과는 즉시 409.
	 */
	@Transactional
	Optional<Long> precheck(long userId, String username, String accountType) {
		List<BrandLinkRow> links = linkRepository.findAllActiveByUser(userId);
		Optional<BrandLinkRow> same = links.stream()
				.filter(link -> link.username().equals(username))
				.findFirst();
		if (same.isPresent()) {
			BrandLinkRow link = same.get();
			if (!accountType.equals(link.accountType())) {
				requireRoom(links, accountType, link.brandId());
				linkRepository.updateAccountType(userId, link.brandId(), accountType);
			}
			return Optional.of(link.brandId());
		}
		requireRoom(links, accountType, null);
		return Optional.empty();
	}

	/**
	 * 저장(§5-1 4단계) — 유저 잠금 → 멱등·한도 재확인 → 활성 연결 생성.
	 * 이미 연결된 브랜드면 타입만 맞추고 성공한다(멱등 — monitoring 등록은 replay라 부작용이 없다).
	 */
	@Transactional
	void link(long userId, long brandId, String username, String accountType) {
		linkRepository.lockUser(userId);
		List<BrandLinkRow> links = linkRepository.findAllActiveByUser(userId);
		Optional<BrandLinkRow> existing = links.stream()
				.filter(link -> link.brandId() == brandId)
				.findFirst();
		if (existing.isPresent()) {
			if (!accountType.equals(existing.get().accountType())) {
				requireRoom(links, accountType, brandId);
				linkRepository.updateAccountType(userId, brandId, accountType);
			}
			return;
		}
		requireRoom(links, accountType, null);
		try {
			linkRepository.insertLink(userId, brandId, username, accountType);
		} catch (DuplicateKeyException e) {
			// (유저, 브랜드) 활성 유니크가 잡은 동시 같은 요청 — 잠금 덕에 사실상 도달 불가지만,
			// 도달해도 원하는 상태(연결됨)는 이미 성립했으므로 멱등 성공으로 접는다.
		}
	}

	/**
	 * 타입 변경(PATCH, 08-12) — 재수집 없이 관계 속성만 바꾼다. 소유권은 활성 연결로 검증하고
	 * (남의 brandId는 403), 대상 타입 상한 초과는 409다. 이미 그 타입이면 조용히 성공(멱등).
	 */
	@Transactional
	void changeType(long userId, long brandId, String accountType) {
		linkRepository.lockUser(userId);
		List<BrandLinkRow> links = linkRepository.findAllActiveByUser(userId);
		BrandLinkRow target = links.stream()
				.filter(link -> link.brandId() == brandId)
				.findFirst()
				.orElseThrow(() -> V1ApiException.forbidden("FORBIDDEN", "브랜드 계정을 찾을 수 없거나 접근 권한이 없어요."));
		if (accountType.equals(target.accountType())) {
			return;
		}
		requireRoom(links, accountType, brandId);
		linkRepository.updateAccountType(userId, brandId, accountType);
	}

	/**
	 * 대상 타입에 자리가 있는지 — 없으면 409. {@code movingBrandId}는 타입을 옮기는 중인 브랜드로,
	 * 그 행은 아직 옛 타입이라 대상 타입 카운트에 들지 않지만 명시적으로 제외해 의도를 드러낸다
	 * (옛 타입 → 새 타입 이동이라 새 타입 쪽 자리만 보면 된다).
	 */
	private static void requireRoom(List<BrandLinkRow> links, String accountType, Long movingBrandId) {
		long used = links.stream()
				.filter(link -> movingBrandId == null || link.brandId() != movingBrandId)
				.filter(link -> accountType.equals(link.accountType()))
				.count();
		if (used >= BrandAccountType.limitOf(accountType)) {
			throw V1ApiException.conflict(BrandAccountType.limitCodeOf(accountType),
					BrandAccountType.limitMessageOf(accountType));
		}
	}
```

클래스 javadoc의 "유저별 한도({@link BrandLinkTransaction#ACCOUNT_LIMIT})" 문장을 "타입별 한도(own 6 / competitor 3 — {@link BrandAccountType})"로 고친다.

- [ ] **Step 4: 서비스·컨트롤러를 새 시그니처에 맞춘다**

`V1BrandAccountService.register`가 타입을 받도록 바꾼다(자세한 본문은 Task 3에서 완성 — 여기서는 컴파일만 통과시킨다):

```java
	public BrandAccountResponse register(long userId, String rawUsername, String rawAccountType) {
		String username = BrandUsername.normalize(rawUsername);
		BrandUsername.validate(username);
		String accountType = BrandAccountType.orDefault(rawAccountType);
		if (!BrandAccountType.isValid(accountType)) {
			throw V1ApiException.validation("accountType 값이 올바르지 않아요.");
		}
		Optional<Long> alreadyLinked = linkTransaction.precheck(userId, username, accountType);
		if (alreadyLinked.isPresent()) {
			return get(userId, alreadyLinked.get());
		}

		BrandRegisterResult registered = translate(() -> commandClient.registerBrand(username));
		try {
			linkTransaction.link(userId, registered.brandId(), username, accountType);
		} catch (RuntimeException e) {
			compensate(registered.brandId(), username);
			throw e;
		}
		// 등록 응답의 status는 monitoring이 "ACTIVE"로 하드코딩해 보내므로 준비 상태 판정에 쓸 수 없다 —
		// 상태는 항상 brand_account 조회가 정본이다(§5-2).
		return get(userId, registered.brandId());
	}
```

컨트롤러의 `register` 호출과 요청 record를 고친다:

```java
	@PostMapping
	public ResponseEntity<ApiResponse<BrandAccountResponse>> register(
			@AuthenticationPrincipal AppUserDetails principal,
			@RequestBody(required = false) BrandAccountRegisterRequest body) {
		String username = body == null ? null : body.username();
		String accountType = body == null ? null : body.accountType();
		BrandAccountResponse response = service.register(principal.getUserId(), username, accountType);
		return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.ok(response));
	}
```

```java
	/** 등록 요청 본문 — 계정명(정규화·검증은 BrandUsername)과 타입(생략 시 own). */
	public record BrandAccountRegisterRequest(String username, String accountType) {
	}
```

`meta.limit`은 Task 4에서 고친다. 지금은 `BrandAccountType.ownLimit() + BrandAccountType.competitorLimit()`로 바꿔 컴파일만 통과시킨다.

- [ ] **Step 5: 테스트 실행**

Run: `./gradlew :was:test --tests "com.celfit.was.v1.brandmonitoring.V1BrandAccountsControllerTest"`
Expected: 상한 3개 테스트는 PASS. `$.data.accountType`을 보는 테스트는 아직 FAIL(Task 3에서 필드 추가). 다른 기존 테스트가 깨지면 헬퍼 시그니처 반영 누락이니 고친다.

- [ ] **Step 6: 커밋**

```bash
git add was/src/main/java/com/celfit/was/v1/brandmonitoring/ was/src/test/java/com/celfit/was/v1/brandmonitoring/
git commit -m "feat(was): 브랜드 구독 상한을 타입별로 분리(own 6 / competitor 3)"
```

---

### Task 3: 응답 필드 accountType + PATCH 엔드포인트

**Files:**
- Modify: `was/src/main/java/com/celfit/was/v1/brandmonitoring/BrandAccountResponse.java`
- Modify: `was/src/main/java/com/celfit/was/v1/brandmonitoring/BrandAccountAssembler.java`
- Modify: `was/src/main/java/com/celfit/was/v1/brandmonitoring/V1BrandAccountService.java`
- Modify: `was/src/main/java/com/celfit/was/v1/brandmonitoring/V1BrandAccountsController.java`
- Test: `was/src/test/java/com/celfit/was/v1/brandmonitoring/V1BrandAccountsControllerTest.java`

**Interfaces:**
- Consumes: `BrandLinkTransaction.changeType(...)`, `BrandAccountType.*`
- Produces: `BrandAccountResponse(String id, String accountType, Profile profile, String collectionStatus, ...)` — `accountType`이 `id` 바로 뒤
- Produces: `BrandAccountAssembler.toResponse(BrandAccountRow row, String accountType)`
- Produces: `V1BrandAccountService.changeType(long userId, long brandId, String rawAccountType)` → `BrandAccountResponse`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

```java
	@Test
	void 목록은_구독_타입을_그대로_돌려준다() throws Exception {
		given(linkRepository.findAllActiveByUser(7L)).willReturn(List.of(
				link(7L, 10L, "my_brand", BrandAccountType.OWN),
				link(7L, 11L, "rival_brand", BrandAccountType.COMPETITOR)));
		given(brandReadRepository.findAccount(10L)).willReturn(Optional.of(readyRow(10L)));
		given(brandReadRepository.findAccount(11L)).willReturn(Optional.of(readyRow(11L)));

		mockMvc.perform(get("/v1/brand-monitoring/accounts").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data[0].accountType").value("own"))
				.andExpect(jsonPath("$.data[1].accountType").value("competitor"))
				.andExpect(jsonPath("$.meta.limits.own").value(6))
				.andExpect(jsonPath("$.meta.limits.competitor").value(3))
				.andExpect(jsonPath("$.meta.counts.own").value(1))
				.andExpect(jsonPath("$.meta.counts.competitor").value(1));
	}

	@Test
	void PATCH는_재수집_없이_타입만_바꾼다() throws Exception {
		given(linkRepository.findAllActiveByUser(7L))
				.willReturn(List.of(link(7L, 10L, "my_brand", BrandAccountType.OWN)))
				.willReturn(List.of(link(7L, 10L, "my_brand", BrandAccountType.COMPETITOR)));
		given(linkRepository.findActiveByUserAndBrand(7L, 10L))
				.willReturn(Optional.of(link(7L, 10L, "my_brand", BrandAccountType.COMPETITOR)));
		given(brandReadRepository.findAccount(10L)).willReturn(Optional.of(readyRow(10L)));

		mockMvc.perform(patch("/v1/brand-monitoring/accounts/10").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"accountType\":\"competitor\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.accountType").value("competitor"));

		then(linkRepository).should().updateAccountType(7L, 10L, "competitor");
		then(commandClient).should(never()).registerBrand(anyString());
	}

	@Test
	void PATCH_남의_계정은_403이다() throws Exception {
		given(linkRepository.findAllActiveByUser(7L)).willReturn(List.of());

		mockMvc.perform(patch("/v1/brand-monitoring/accounts/999").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"accountType\":\"competitor\"}"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
	}

	@Test
	void PATCH_값_공간_밖_타입은_400이다() throws Exception {
		mockMvc.perform(patch("/v1/brand-monitoring/accounts/10").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"accountType\":\"rival\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
	}

	@Test
	void PATCH_숫자가_아닌_id는_404다() throws Exception {
		mockMvc.perform(patch("/v1/brand-monitoring/accounts/abc").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"accountType\":\"competitor\"}"))
				.andExpect(status().isNotFound());
	}

	@Test
	void 재등록으로_타입을_바꾸면_monitoring을_호출하지_않는다() throws Exception {
		given(linkRepository.findAllActiveByUser(7L))
				.willReturn(List.of(link(7L, 10L, "my_brand", BrandAccountType.OWN)));
		given(linkRepository.findActiveByUserAndBrand(7L, 10L))
				.willReturn(Optional.of(link(7L, 10L, "my_brand", BrandAccountType.COMPETITOR)));
		given(brandReadRepository.findAccount(10L)).willReturn(Optional.of(readyRow(10L)));

		mockMvc.perform(post("/v1/brand-monitoring/accounts").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"username\":\"my_brand\",\"accountType\":\"competitor\"}"))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.data.accountType").value("competitor"));

		then(linkRepository).should().updateAccountType(7L, 10L, "competitor");
		then(commandClient).should(never()).registerBrand(anyString());
	}
```

import에 `org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch`를 추가한다.

- [ ] **Step 2: 테스트가 실패하는지 확인**

Run: `./gradlew :was:test --tests "com.celfit.was.v1.brandmonitoring.V1BrandAccountsControllerTest"`
Expected: FAIL — PATCH 매핑 없음(405), `accountType` 필드 없음, `meta.limits` 없음.

- [ ] **Step 3: 응답 record에 필드 추가**

`BrandAccountResponse.java`:

```java
public record BrandAccountResponse(String id, String accountType, Profile profile, String collectionStatus,
		String collectionStartedAt, String collectionCompletedAt, String lastDetectedAt,
		String lastTrackedAt, String nextScheduledAt, CollectionError collectionError, String createdAt) {
```

javadoc에 한 줄 추가:

```java
 * <p>{@code accountType}은 brand_account가 아니라 <b>구독</b>(app.brand_monitorings)의 속성이다 —
 * 같은 브랜드라도 유저마다 own/competitor가 다를 수 있다(08-12).
```

- [ ] **Step 4: 조립기 시그니처 변경**

`BrandAccountAssembler.toResponse`를 `public BrandAccountResponse toResponse(BrandAccountRow row, String accountType)`로 바꾸고, 생성자 호출에서 `String.valueOf(row.id())` 다음 인자로 `accountType`을 넣는다. 조립기는 계속 순수 변환이다.

- [ ] **Step 5: 서비스에 타입을 관통시키고 changeType을 추가**

`V1BrandAccountService`:

```java
	/** 목록(§5-2) — 유저의 활성 연결 전체(연결 순). accountType은 연결 행에서 온다(08-12). */
	public List<BrandAccountResponse> list(long userId) {
		List<BrandAccountResponse> accounts = new ArrayList<>();
		for (BrandLinkRow link : linkRepository.findAllActiveByUser(userId)) {
			Optional<BrandAccountRow> row = brandReadRepository.findAccount(link.brandId());
			if (row.isEmpty()) {
				// 도달 불가(등록이 monitoring 먼저라 연결이 있으면 brand_account도 있다). 목록 전체를
				// 500으로 떨구는 대신 그 건만 빼고 돌려주고 로그로 드러낸다 — 폴링 화면이 죽지 않게.
				log.warn("활성 연결의 brand_account 부재 — 목록에서 제외 userId={}, brandId={}",
						userId, link.brandId());
				continue;
			}
			accounts.add(assembler.toResponse(row.get(), link.accountType()));
		}
		return List.copyOf(accounts);
	}

	/** 단건 폴링(§5-2) — 소유권은 활성 연결로 검증(남의 brandId는 403). 타입도 그 연결에서 읽는다. */
	public BrandAccountResponse get(long userId, long brandId) {
		BrandLinkRow link = requireOwnership(userId, brandId);
		return assembler.toResponse(findAccountOrThrow(brandId), link.accountType());
	}

	/**
	 * 타입 변경(§2-3, 08-12) — 재수집 없이 구독 속성만 바꾼다. 상한 초과는 409, 남의 계정은 403.
	 * POST 재등록의 타입 변경과 같은 트랜잭션 메서드를 쓴다(판정이 한 곳에만 있게).
	 */
	public BrandAccountResponse changeType(long userId, long brandId, String rawAccountType) {
		String accountType = BrandAccountType.orDefault(rawAccountType);
		if (!BrandAccountType.isValid(accountType)) {
			throw V1ApiException.validation("accountType 값이 올바르지 않아요.");
		}
		linkTransaction.changeType(userId, brandId, accountType);
		return get(userId, brandId);
	}
```

`requireOwnership`이 행을 돌려주도록 바꾼다:

```java
	private BrandLinkRow requireOwnership(long userId, long brandId) {
		return linkRepository.findActiveByUserAndBrand(userId, brandId)
				.orElseThrow(() -> V1ApiException.forbidden("FORBIDDEN", "브랜드 계정을 찾을 수 없거나 접근 권한이 없어요."));
	}
```

- [ ] **Step 6: 컨트롤러에 PATCH와 meta 추가**

```java
	@GetMapping
	public ApiResponse<List<BrandAccountResponse>> list(@AuthenticationPrincipal AppUserDetails principal) {
		List<BrandAccountResponse> accounts = service.list(principal.getUserId());
		long own = accounts.stream().filter(a -> BrandAccountType.OWN.equals(a.accountType())).count();
		Map<String, Object> meta = new LinkedHashMap<>();
		meta.put("total", accounts.size());
		// limit은 호환용으로 남긴 합산 최대다(타입별로 갈려 단일 값이 의미를 잃었다) — 실제 게이트는
		// limits·counts고, 강제 지점은 BrandLinkTransaction이다.
		meta.put("limit", BrandAccountType.ownLimit() + BrandAccountType.competitorLimit());
		meta.put("limits", Map.of(BrandAccountType.OWN, BrandAccountType.ownLimit(),
				BrandAccountType.COMPETITOR, BrandAccountType.competitorLimit()));
		meta.put("counts", Map.of(BrandAccountType.OWN, own,
				BrandAccountType.COMPETITOR, accounts.size() - own));
		return ApiResponse.ok(accounts, meta);
	}

	/** 타입 변경(§2-3, 08-12) — 재수집 없이 구독 속성만 바꾼다. 200 + 갱신된 계정 객체. */
	@PatchMapping("/{accountId}")
	public ApiResponse<BrandAccountResponse> changeType(@AuthenticationPrincipal AppUserDetails principal,
			@PathVariable String accountId, @RequestBody(required = false) BrandAccountTypeRequest body) {
		String accountType = body == null ? null : body.accountType();
		return ApiResponse.ok(service.changeType(principal.getUserId(), parseAccountId(accountId), accountType));
	}
```

```java
	/** 타입 변경 요청 본문 — own|competitor. */
	public record BrandAccountTypeRequest(String accountType) {
	}
```

import에 `org.springframework.web.bind.annotation.PatchMapping`을 추가한다.

**주의**: `parseAccountId`가 `@PatchMapping` 경로에서도 먼저 돌아야 숫자 아닌 id가 404가 된다 — 위 코드처럼 `service.changeType` 인자 안에서 호출하면 순서가 보장된다. 다만 `accountType` 검증(400)보다 id 파싱(404)이 먼저다: 잘못된 id + 잘못된 타입이면 404가 이긴다. 테스트도 그 순서를 고정한다.

- [ ] **Step 7: 테스트 실행**

Run: `./gradlew :was:test --tests "com.celfit.was.v1.brandmonitoring.V1BrandAccountsControllerTest"`
Expected: PASS 전부.

- [ ] **Step 8: 커밋**

```bash
git add was/src/main/java/com/celfit/was/v1/brandmonitoring/ was/src/test/java/com/celfit/was/v1/brandmonitoring/
git commit -m "feat(was): 브랜드 계정 응답에 accountType 추가 + PATCH 타입 변경 엔드포인트"
```

---

### Task 4: 성과 대시보드 — contents 필터 + comparison 필드

**Files:**
- Modify: `was/src/main/java/com/celfit/was/v1/perfdashboard/PerformanceContentAssembler.java`
- Modify: `was/src/main/java/com/celfit/was/v1/perfdashboard/V1PerformanceDashboardController.java`
- Modify: `was/src/main/java/com/celfit/was/v1/perfdashboard/PerformanceComparisonResponse.java`
- Modify: `was/src/main/java/com/celfit/was/v1/perfdashboard/PerformanceComparisonAssembler.java`
- Test: `was/src/test/java/com/celfit/was/v1/perfdashboard/` 아래 기존 테스트 클래스(없으면 `V1PerformanceDashboardAccountTypeTest.java` 신규)

**Interfaces:**
- Consumes: `BrandLinkRepository.findAllActiveByUser`, `BrandAccountType.*`
- Produces: `PerformanceContentAssembler.Assembled`에 `Set<String> competitorBrandAccountIds` 추가 — 콘텐츠의 `brandAccountId`가 이 집합에 들면 경쟁사 소속
- Produces: `PerformanceComparisonResponse.AccountComparison(String brandAccountId, String username, String accountType, String collectionStartedAt, List<Bucket> buckets)` — `accountType`이 `username` 뒤

- [ ] **Step 1: 실패하는 테스트를 쓴다**

기존 성과 대시보드 테스트 클래스의 관용구(브랜드 링크·콘텐츠 mock 구성)를 그대로 따라 다음 4개를 추가한다:

```java
	@Test
	void contents_기본은_경쟁사만_제외하고_개인추적은_포함한다() throws Exception {
		// own 브랜드 1건 + 경쟁사 1건 + 개인추적(individual, brandAccountId null) 1건
		mockMvc.perform(get("/v1/performance-dashboard/contents").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.length()").value(2))
				.andExpect(jsonPath("$.data[?(@.brandAccountId == '11')]").doesNotExist());
	}

	@Test
	void contents_accountType_competitor는_경쟁사만_돌려준다() throws Exception {
		mockMvc.perform(get("/v1/performance-dashboard/contents")
						.param("accountType", "competitor").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.length()").value(1))
				.andExpect(jsonPath("$.data[0].brandAccountId").value("11"));
	}

	@Test
	void contents_accountType_all은_전부_돌려주고_statusCounts_모수도_같다() throws Exception {
		mockMvc.perform(get("/v1/performance-dashboard/contents")
						.param("accountType", "all").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.length()").value(3))
				.andExpect(jsonPath("$.meta.statusCounts.tracking").value(3));
	}

	@Test
	void contents_값_공간_밖_accountType은_400이다() throws Exception {
		mockMvc.perform(get("/v1/performance-dashboard/contents")
						.param("accountType", "rival").with(user(principal())))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
	}

	@Test
	void comparison은_계정별_accountType을_내리고_경쟁사도_포함한다() throws Exception {
		mockMvc.perform(get("/v1/performance-dashboard/comparison").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.accounts[0].accountType").value("own"))
				.andExpect(jsonPath("$.data.accounts[1].accountType").value("competitor"));
	}
```

- [ ] **Step 2: 테스트가 실패하는지 확인**

Run: `./gradlew :was:test --tests "com.celfit.was.v1.perfdashboard.*"`
Expected: FAIL — `accountType` 파라미터가 무시돼 기본에서도 3건이 나오고, comparison에 필드가 없다.

- [ ] **Step 3: 어셈블러가 경쟁사 집합을 노출하게 한다**

`PerformanceContentAssembler`의 `Assembled` record에 필드를 더한다. 어셈블러는 이미 `linkRepository.findAllActiveByUser(userId)`를 부르므로, 그 순회에서 경쟁사 brandId를 모은다(추가 쿼리 없음):

```java
	/**
	 * @param competitorBrandAccountIds 경쟁사 구독의 brandAccountId 집합(08-12) — 성과 요약이
	 *        경쟁사 숫자로 오염되지 않도록 컨트롤러가 기본 필터에 쓴다. 브랜드 미귀속(individual)
	 *        콘텐츠는 이 집합에 들 수 없어 기본 범위에 그대로 남는다.
	 */
	public record Assembled(List<PerformanceContentResponse> contents, OffsetDateTime lastCollectedAt,
			Set<String> competitorBrandAccountIds) {
	}
```

링크 순회 지점(현재 `String.valueOf(link.brandId())`로 계정을 여는 곳)에서:

```java
		Set<String> competitorIds = new LinkedHashSet<>();
		for (BrandLinkRow link : links) {
			if (BrandAccountType.COMPETITOR.equals(link.accountType())) {
				competitorIds.add(String.valueOf(link.brandId()));
			}
			// ... 기존 tagged 조립 로직 그대로
		}
```

monitoring 비활성 등으로 링크를 읽지 않는 경로에서는 빈 집합을 넘긴다.

- [ ] **Step 4: 컨트롤러에 필터를 추가한다**

`V1PerformanceDashboardController.contents`에 파라미터를 더하고 분류 술어에 넣는다:

```java
			@RequestParam(required = false) String accountType) {
		...
		String accountTypeFilter = normalizeFilter(accountType, "accountType",
				BrandAccountType.OWN, BrandAccountType.COMPETITOR);
		...
		PerformanceContentAssembler.Assembled assembled = assembler.assemble(principal.getUserId());
		Set<String> competitorIds = assembled.competitorBrandAccountIds();

		Predicate<PerformanceContentResponse> classification = c ->
				(sourceFilter == null || sourceFilter.equals(c.source()))
						&& (sponsorshipFilter == null || sponsorshipFilter.equals(c.sponsorship()))
						&& matchesCampaign(c, campaignFilter)
						&& (brandFilter == null || brandFilter.equals(c.brandAccountId()))
						&& matchesAccountType(c, accountTypeFilter, competitorIds);
```

```java
	/**
	 * accountType 필터(08-12) — {@code all}(=null)은 전량, {@code competitor}는 경쟁사 구독 소속만,
	 * <b>미지정·own은 "경쟁사만 제외"</b>다.
	 *
	 * <p>미지정이 "own 브랜드만"이 아닌 이유: 이 응답에는 브랜드에 귀속되지 않는 레거시 개인 추적
	 * 콘텐츠(brandAccountId null)가 섞여 있어, 문자 그대로 own만 남기면 경쟁사를 하나도 등록하지
	 * 않은 유저의 성과 요약 숫자까지 줄어든다. 요청서의 의도(경쟁사가 내 성과를 오염시키지 않게)는
	 * 경쟁사만 빼는 것으로 충족된다(스펙 §5).
	 */
	private static boolean matchesAccountType(PerformanceContentResponse content, String filter,
			Set<String> competitorIds) {
		boolean competitor = content.brandAccountId() != null
				&& competitorIds.contains(content.brandAccountId());
		if (BrandAccountType.COMPETITOR.equals(filter)) {
			return competitor;
		}
		if (filter == null) {
			// all 또는 미지정 — 둘을 여기서 가른다(normalizeFilter가 all과 미지정을 같은 null로 접는다).
			return true;
		}
		return !competitor;
	}
```

**주의**: `normalizeFilter`는 미지정과 `all`을 똑같이 null로 접는다. 그런데 이 파라미터는 **미지정 = 경쟁사 제외**, **`all` = 전량**으로 서로 달라야 한다. 그래서 `normalizeFilter`를 쓰지 말고 전용 정규화를 쓴다:

```java
	/**
	 * accountType 전용 정규화 — 다른 필터와 달리 미지정과 {@code all}이 다르다(미지정은 경쟁사
	 * 제외가 기본, all은 전량). 그래서 공용 normalizeFilter를 쓰지 않는다.
	 * 반환: null = 전량(all), "own" = 경쟁사 제외, "competitor" = 경쟁사만.
	 */
	private static String normalizeAccountType(String raw) {
		if (raw == null || raw.isBlank()) {
			return BrandAccountType.OWN;
		}
		if (FILTER_ALL.equals(raw)) {
			return null;
		}
		if (!BrandAccountType.isValid(raw)) {
			throw V1ApiException.validation("accountType 값이 올바르지 않아요.");
		}
		return raw;
	}
```

Step 4의 `normalizeFilter(accountType, "accountType", ...)` 호출을 `normalizeAccountType(accountType)`으로 바꾼다.

`comparison` 메서드에도 같은 파라미터를 추가하지 **않는다** — 비교 화면은 정의상 own·competitor를 나란히 놓는 것이라 필터가 없다(스펙 §6).

- [ ] **Step 5: comparison에 필드를 추가한다**

`PerformanceComparisonResponse.AccountComparison`에 `accountType`을 `username` 뒤로 넣고 javadoc을 한 줄 고친다:

```java
	/**
	 * 브랜드 계정 1개의 비교 축 — collectionStartedAt은 brand_account.registered_at(KST ISO).
	 * accountType은 구독 속성이다(own/competitor, 08-12) — 이 응답은 둘 다 포함한다(나란히 비교가
	 * 이 화면의 존재 이유다).
	 */
	public record AccountComparison(String brandAccountId, String username, String accountType,
			String collectionStartedAt, List<Bucket> buckets) {
	}
```

`PerformanceComparisonAssembler`의 생성 지점에서 순회 중인 `link.accountType()`을 넘긴다.

- [ ] **Step 6: 테스트 실행**

Run: `./gradlew :was:test --tests "com.celfit.was.v1.perfdashboard.*"`
Expected: PASS 전부.

- [ ] **Step 7: 커밋**

```bash
git add was/src/main/java/com/celfit/was/v1/perfdashboard/ was/src/test/java/com/celfit/was/v1/perfdashboard/
git commit -m "feat(was): 성과 대시보드에 accountType 필터·필드 추가"
```

---

### Task 5: 캠페인 연결 서버 방어

**Files:**
- Modify: `was/src/main/java/com/celfit/was/v2/monitoring/V2CampaignContentService.java`
- Test: `was/src/test/java/com/celfit/was/v2/monitoring/V2CampaignContentsControllerTest.java` (기존 테스트 클래스명이 다르면 그 파일)

**Interfaces:**
- Consumes: `BrandAccountType.COMPETITOR`, `BrandLinkRow.accountType()`
- Produces: `V2CampaignContentService.REASON_CODE_COMPETITOR_NOT_ALLOWED = "COMPETITOR_CONTENT_NOT_ALLOWED"`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

```java
	@Test
	void 경쟁사_게시물은_건별_실패고_같은_요청의_정상_콘텐츠는_성공한다() throws Exception {
		// own 브랜드 태그 게시물 OWNPOST1, 경쟁사 브랜드 태그 게시물 RIVALPOST1

		mockMvc.perform(post("/v2/monitoring/campaigns/5/contents").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"contentIds\":[\"OWNPOST1\",\"RIVALPOST1\"],\"trackingDays\":30}"))
				.andExpect(jsonPath("$.data.results[1].contentId").value("RIVALPOST1"))
				.andExpect(jsonPath("$.data.results[1].result").value("failed"))
				.andExpect(jsonPath("$.data.results[1].reasonCode").value("COMPETITOR_CONTENT_NOT_ALLOWED"))
				.andExpect(jsonPath("$.data.results[0].result").value(Matchers.not("failed")));
	}

	@Test
	void 경쟁사_게시물은_NOT_FOUND가_아니다() throws Exception {
		mockMvc.perform(post("/v2/monitoring/campaigns/5/contents").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"contentIds\":[\"RIVALPOST1\"],\"trackingDays\":30}"))
				.andExpect(jsonPath("$.data.results[0].reasonCode")
						.value(Matchers.not("NOT_FOUND")));
	}
```

- [ ] **Step 2: 테스트가 실패하는지 확인**

Run: `./gradlew :was:test --tests "com.celfit.was.v2.monitoring.*"`
Expected: FAIL — 경쟁사 게시물이 그대로 등록에 위임돼 success/pending이 된다.

- [ ] **Step 3: 태그 맵에 타입을 실어 건별로 거절한다**

`V2CampaignContentService`에 상수와 사유 문장을 더한다:

```java
	/** 경쟁사 구독 게시물의 캠페인 연결 차단(08-12 FE 요청 §3-3) — 건별 실패 사유다. */
	static final String REASON_CODE_COMPETITOR_NOT_ALLOWED = "COMPETITOR_CONTENT_NOT_ALLOWED";
	private static final String REASON_COMPETITOR_NOT_ALLOWED = "경쟁사 계정의 게시물은 캠페인에 연결할 수 없어요.";
```

`taggedPostUrls`를 URL+타입을 담는 맵으로 바꾼다:

```java
	/** 태그 목록 1건 — canonical URL과 그 게시물을 실어 온 구독의 타입(08-12). */
	private record TaggedPost(String url, String accountType) {
	}

	/**
	 * 내 브랜드 태그 목록의 shortcode → (canonical URL, 구독 타입). monitoring 비활성·브랜드 연결
	 * 없음·계정 행 부재면 빈 맵이라 그 콘텐츠는 그대로 failed(NOT_FOUND)가 된다.
	 *
	 * <p>경쟁사 구독도 <b>맵에 담는다</b>(08-12) — 빼버리면 경쟁사 게시물이 NOT_FOUND로 떨어져
	 * "존재하는 게시물을 없다고" 말하게 된다. 담아 두고 판정 지점에서 전용 사유로 거절한다.
	 */
	private Map<String, TaggedPost> taggedPostUrls(long userId) {
		if (brandReadRepository.isEmpty() || brandPostAssembler.isEmpty()) {
			return Map.of();
		}
		// 다계정(08-07 개정) — 연결된 브랜드 전체의 태그 목록을 연결 순으로 병합한다(먼저 연결한 브랜드 우선).
		Map<String, TaggedPost> urls = new LinkedHashMap<>();
		for (BrandLinkRow link : linkRepository.findAllActiveByUser(userId)) {
			Optional<BrandAccountRow> account = brandReadRepository.get().findAccount(link.brandId());
			if (account.isEmpty()) {
				continue;
			}
			for (BrandPostResponse post : brandPostAssembler.get().assembleTagged(account.get())) {
				urls.putIfAbsent(post.shortcode(), new TaggedPost(post.postUrl(), link.accountType()));
			}
		}
		return urls;
	}
```

`add()`의 판정 루프를 고친다:

```java
			if (taggedUrls == null) {
				taggedUrls = taggedPostUrls(userId);
			}
			TaggedPost tagged = taggedUrls.get(contentId);
			if (tagged == null) {
				plans.add(Plan.settled(new Result(contentId, RegistrationResult.FAILED, null,
						REASON_CODE_NOT_FOUND, REASON_NOT_FOUND)));
				continue;
			}
			// 경쟁사 구독 게시물은 캠페인에 연결하지 않는다(§3-3) — 요청 전체를 400으로 떨구지 않고
			// 건별 실패다. 이 API는 100건 배치의 부분 성공이 계약이라, 1건 때문에 나머지를 버리지 않는다.
			if (BrandAccountType.COMPETITOR.equals(tagged.accountType())) {
				plans.add(Plan.settled(new Result(contentId, RegistrationResult.FAILED, null,
						REASON_CODE_COMPETITOR_NOT_ALLOWED, REASON_COMPETITOR_NOT_ALLOWED)));
				continue;
			}
			plans.add(new Plan(contentId, delegatedUrls.size(), false, null));
			delegatedUrls.add(tagged.url());
```

클래스 javadoc의 tagged 경로 설명에 한 줄 더한다: "경쟁사 구독(accountType=competitor) 게시물은 이 경로에서 건별 실패로 거절한다(08-12)."

- [ ] **Step 4: 테스트 실행**

Run: `./gradlew :was:test --tests "com.celfit.was.v2.monitoring.*"`
Expected: PASS 전부.

- [ ] **Step 5: 커밋**

```bash
git add was/src/main/java/com/celfit/was/v2/monitoring/ was/src/test/java/com/celfit/was/v2/monitoring/
git commit -m "feat(was): 경쟁사 구독 게시물의 캠페인 연결 차단(건별 실패)"
```

---

### Task 6: 모듈 전체 검증 + 문서 갱신 + PR

**Files:**
- Modify: `DECISIONS.md`
- Create: `docs/tracks/PP-경쟁사-계정-타입.md`
- Modify: `docs/superpowers/specs/2026-08-12-competitor-account-type-design.md` (상태 헤더)

- [ ] **Step 1: was 모듈 전체 테스트**

```bash
./gradlew :was:test
```
Expected: PASS. 대량 실패가 나오면 도커 접속을 먼저 의심한다(테스트 결함으로 오진하기 쉽다) — 이 머신은 Docker Desktop이므로 `DOCKER_HOST`는 **비어 있어야** 한다. `docker ps`가 되는지부터 확인할 것.

- [ ] **Step 2: 마이그레이션 가드 + 전체 빌드**

```bash
.github/scripts/check-migration-safety.sh
./gradlew build -x test
```
Expected: 둘 다 PASS.

- [ ] **Step 3: 트랙 문서 작성**

`docs/tracks/PP-경쟁사-계정-타입.md`:

```markdown
# PP — 경쟁사 계정 타입(accountType)

- **설계**: [specs/2026-08-12-competitor-account-type-design.md](../superpowers/specs/2026-08-12-competitor-account-type-design.md)
- **FE 회신**: 채팅으로 전달(08-12) — 문서로 남기지 않았다
- **의존**: 브랜드 모니터링 was 표면(08-07 다계정 개정)
- **상태**: 🔵 구현 완료 — PR 리뷰 대기

## 내용

브랜드 구독(`app.brand_monitorings`)에 `account_type`(own/competitor)을 추가하고 계정 API 응답·
타입별 상한(own 6 / competitor 3, 409 강제)·PATCH 타입 변경·성과 대시보드 `/contents` 필터와
`comparison` 필드·캠페인 연결 서버 방어까지 관통시켰다. 타입은 계정이 아니라 유저-계정 관계의
속성이라 관계 테이블에만 저장한다(같은 브랜드가 유저마다 다른 타입일 수 있다).

FE 요청서와 다르게 간 지점 4개(한도 초과 409 유지·캠페인 방어 건별 실패·`/contents` 기본에
individual 포함·`meta.limit` 값 변경)는 설계 문서 §요청서와 다른 점에 근거를 남겼다.
```

- [ ] **Step 4: DECISIONS.md 맨 위에 결정 추가**

```markdown
## 2026-08-12 — 브랜드 구독 타입(own/competitor)은 관계 테이블에만 둔다

FE 경쟁사 모니터링 요청(08-11)에 따라 `app.brand_monitorings.account_type`을 추가했다.
같은 인스타 계정이 유저마다 다른 타입일 수 있어(담당 브랜드 vs 경쟁사) `brand_account`에
두면 한 유저의 지정이 다른 유저 화면을 바꾼다. 수집은 브랜드당 전역 1회를 유지한다.

상한은 타입별(own 6 / competitor 3)로 갈리고 초과는 409다 — 요청서는 400을 적었지만
POST가 이미 409 `BRAND_ACCOUNT_LIMIT_REACHED`를 내리고 FE가 그 코드로 분기 중이라,
같은 사건이 경로마다 다른 상태가 되는 것을 피했다. competitor만 코드를 신설했다.

캠페인 연결 방어도 요청 전체 400이 아니라 건별 실패(`COMPETITOR_CONTENT_NOT_ALLOWED`)다 —
그 엔드포인트는 100건 배치의 부분 성공이 계약이라, 경쟁사 1건 때문에 정상 99건을 버리지 않는다.
```

- [ ] **Step 5: 설계 문서 상태 헤더 갱신**

`> 상태: 🟢 활성` → `> 상태: ✅ 구현됨`

- [ ] **Step 6: 커밋 + PR**

```bash
git add DECISIONS.md docs/
git commit -m "docs: 경쟁사 계정 타입 트랙 문서·결정 기록"
git push -u origin feature/competitor-monitoring-api-spec-b730b6
gh pr create --base develop --title "feat(was): 경쟁사 모니터링 계정 타입(accountType)" --body "$(cat <<'EOF'
## 요약

브랜드 구독에 `own`/`competitor` 타입을 추가하고 계정 API·상한·성과 대시보드·캠페인 연결까지 관통시켰다.
FE 요청서(08-11) P0~P2 전부.

- `app.brand_monitorings.account_type` 추가(기존 행은 DEFAULT로 `own` 백필)
- 타입별 상한 own 6 / competitor 3 — 409 강제
- `PATCH /v1/brand-monitoring/accounts/{id}` 신설, POST 재등록도 타입 변경으로 동작
- `/performance-dashboard/contents`에 `accountType` 필터(기본 = 경쟁사만 제외), `comparison`에 필드 추가
- 경쟁사 게시물의 캠페인 연결을 건별 실패로 차단

## 요청서와 다르게 간 지점

설계 문서 §요청서와 다른 점 참조 — 한도 초과 409 유지, 캠페인 방어 건별 실패,
`/contents` 기본에 individual 포함, `meta.limit` 값 변경. FE에는 채팅으로 회신했다.

## 테스트

`./gradlew :was:test` 전체 통과.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

---

## Self-Review

**Spec coverage**

| 스펙 절 | 담당 |
|---|---|
| §1 데이터 모델 | Task 1 |
| §2 상한 | Task 2 (강제) · Task 3 (meta) |
| §3 등록·타입 변경 | Task 2 (트랜잭션) · Task 3 (표면) |
| §4 응답 필드 | Task 3 |
| §5 contents 범위 | Task 4 |
| §6 comparison | Task 4 |
| §7 캠페인 방어 | Task 5 |
| 테스트 계획 표 | Task 2~5 각 Step 1 |
| 문서·결정 기록 | Task 6 |

**Type consistency 확인**

- `BrandAccountType`: `OWN`/`COMPETITOR`/`isValid`/`orDefault`/`limitOf`/`limitCodeOf`/`limitMessageOf`/`ownLimit`/`competitorLimit` — Task 1에서 정의, Task 2·3·4·5에서 같은 이름으로 사용.
- `BrandLinkRow`: `accountType`이 `username` 뒤 — Task 1 정의, Task 2 테스트 헬퍼·Task 4·5 소비가 같은 순서.
- `insertLink(userId, brandId, username, accountType)` — Task 1 정의, Task 2 사용.
- `updateAccountType(userId, brandId, accountType)` → `boolean` — Task 1 정의, Task 2 사용, Task 3 테스트가 검증.
- `toResponse(row, accountType)` — Task 3에서 시그니처 변경, 같은 Task 안에서만 호출.
- `Assembled(contents, lastCollectedAt, competitorBrandAccountIds)` — Task 4 안에서 정의·소비.
- `AccountComparison(brandAccountId, username, accountType, collectionStartedAt, buckets)` — Task 4 안에서 정의·소비.

**알려진 순서 의존**: Task 1 Step 6이 `insertLink` 호출을 `OWN` 하드코딩으로 임시 고정한다 — Task 2 Step 3이 이를 실제 타입으로 교체한다. Task 2 없이 Task 1만 머지하면 모든 신규 구독이 own이 되지만(현행 동작과 동일) 계약은 깨지지 않는다.
