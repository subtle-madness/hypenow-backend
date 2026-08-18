# 브랜드 연결별 표시 기간(링크 레벨 collection_months) 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 유저가 브랜드 등록 시 신청한 기간(1|3|6|12개월)을 `app.brand_monitorings.collection_months`에 저장하고, 게시물 목록·상세·계정 응답을 그 기간으로 잘라 서빙한다.

**Architecture:** 크롤 자산(`monitoring brand_account.collection_months`, 유저 간 max)은 불변. 유저-브랜드 링크 테이블에 표시 창 컬럼을 신설하고, was 서빙 계층(컨트롤러)에서만 자른다. 스펙: [docs/superpowers/specs/2026-08-17-brand-link-collection-months-design.md](../../specs/2026-08-17-brand-link-collection-months-design.md).

**Tech Stack:** Java 21, Spring Boot 4.1, JdbcClient, Flyway(app 스키마), @WebMvcTest 슬라이스 + Testcontainers 통합 테스트.

## Global Constraints

- 테스트는 모듈 단위: `./gradlew :was:test` (전체 `./gradlew test`는 PR 직전에만).
- 통합 테스트 전 셸에 `export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock` — 단, 이 머신은 Docker Desktop 사용 중이면 미설정이 정답(메모리 `local-docker-desktop-not-colima` 참조). 대량 실패 시 이것부터 확인.
- 신규 Flyway 마이그레이션은 **UTC 타임스탬프 채번**: `V$(date -u +%Y%m%d%H%M%S)__<설명>.sql`. KST 채번 금지.
- 스키마 변경은 expand-contract — 이번 변경은 신규 컬럼 + DEFAULT뿐이라 가드 통과.
- 주석·커밋 메시지는 한국어, 커밋 prefix `feat(was):`/`docs:`.
- 값 공간 1|3|6|12의 단일 정의는 기존 `BrandCollectionMonths`(was) — 새 상수 클래스를 만들지 않는다.
- 크롤 자산 로직(monitoring 모듈, was의 자산 확장 게이트)은 **건드리지 않는다**.

---

### Task 1: 마이그레이션 + BrandLinkRow/BrandLinkRepository 확장

링크 테이블에 `collection_months`를 추가하고 저장 계층을 확장한다. `insertLink` 시그니처가 바뀌므로 호출부(BrandLinkTransaction·서비스·테스트)의 기계적 수정까지 이 태스크에서 끝내 컴파일을 유지한다. 동작 변화는 "신규 연결이 신청값을 저장한다"까지만 — 재등록 갱신·응답·서빙 창은 Task 2·3.

**Files:**
- Create: `was/src/main/resources/db/migration/app/V<UTC타임스탬프>__brand_monitorings_collection_months.sql`
- Modify: `was/src/main/java/com/celfit/was/monitoring/BrandLinkRow.java`
- Modify: `was/src/main/java/com/celfit/was/monitoring/BrandLinkRepository.java`
- Modify: `was/src/main/java/com/celfit/was/v1/brandmonitoring/BrandLinkTransaction.java` (link에 months 전달)
- Modify: `was/src/main/java/com/celfit/was/v1/brandmonitoring/V1BrandAccountService.java:102` (link 호출 1줄)
- Test: `was/src/test/java/com/celfit/was/monitoring/BrandLinkRepositoryTest.java`
- Modify(컴파일 유지 — `new BrandLinkRow(` 생성자에 6번째 인자 `12` 추가):
  - `was/src/test/java/com/celfit/was/v1/admin/AdminCrawlingUsageServiceTest.java` (2곳)
  - `was/src/test/java/com/celfit/was/v1/brandmonitoring/V1BrandAccountServiceWithdrawalTest.java` (1곳)
  - `was/src/test/java/com/celfit/was/v1/brandmonitoring/V1BrandAccountsControllerTest.java` (1곳 — link 헬퍼)
  - `was/src/test/java/com/celfit/was/v1/brandmonitoring/V1BrandDirectPostServiceTest.java` (1곳)
  - `was/src/test/java/com/celfit/was/v1/brandmonitoring/V1BrandPostsControllerTest.java` (1곳 — link 헬퍼)
  - `was/src/test/java/com/celfit/was/v1/perfdashboard/PerformanceComparisonAssemblerTest.java` (1곳)
  - `was/src/test/java/com/celfit/was/v1/perfdashboard/PerformanceContentAssemblerTest.java` (11곳)
  - `was/src/test/java/com/celfit/was/v2/monitoring/V2CampaignContentServiceTest.java` (1곳)
  - `was/src/test/java/com/celfit/was/monitoring/BrandLinkRepositoryTest.java` 기존 `insertLink(...)` 4-인자 호출 전부에 `, 12` 추가

**Interfaces:**
- Produces: `BrandLinkRow(long id, long userId, long brandId, String username, String accountType, int collectionMonths, OffsetDateTime createdAt, OffsetDateTime deletedAt)` — **collectionMonths는 accountType 다음, createdAt 앞**.
- Produces: `BrandLinkRepository.insertLink(long userId, long brandId, String username, String accountType, int collectionMonths)` (5-인자, RETURNING id 동일)
- Produces: `BrandLinkRepository.updateCollectionMonths(long userId, long brandId, int collectionMonths)` → `boolean` (활성 행 있었으면 true — `updateAccountType` 동형)
- Produces: `BrandLinkTransaction.link(long userId, long brandId, String username, String accountType, int collectionMonths)` (5-인자)

- [ ] **Step 1: 마이그레이션 파일 생성**

채번은 반드시 실행 시점에:

```bash
date -u +%Y%m%d%H%M%S
```

`was/src/main/resources/db/migration/app/V<출력값>__brand_monitorings_collection_months.sql`:

```sql
-- 유저별 표시 기간(2026-08-17 스펙) — 등록 시 신청한 수집 범위를 연결(관계) 레벨에 저장한다.
-- 크롤 자산(monitoring brand_account.collection_months, 유저 간 max)과 별개의 표시 창이다.
-- 기존 행은 12(현행 표시 그대로 — 신청값이 영속화된 적이 없어 복원 불가, 스펙 §결정 요약).
ALTER TABLE app.brand_monitorings
    ADD COLUMN collection_months int NOT NULL DEFAULT 12
    CHECK (collection_months IN (1, 3, 6, 12));
```

- [ ] **Step 2: BrandLinkRow에 collectionMonths 추가**

`BrandLinkRow.java` — record 시그니처 교체 + javadoc 한 단락 추가:

```java
/**
 * <p>{@code collectionMonths}(2026-08-17)는 이 유저가 신청한 <b>표시 기간</b>이다(1|3|6|12) —
 * 크롤 자산 {@code brand_account.collection_months}(유저 간 max)와 별개로, 서빙 창을 자르는 기준.
 * 같은 브랜드라도 유저마다 다를 수 있어 accountType처럼 관계 속성으로 여기 있다.
 */
public record BrandLinkRow(long id, long userId, long brandId, String username, String accountType,
		int collectionMonths, OffsetDateTime createdAt, OffsetDateTime deletedAt) {
}
```

- [ ] **Step 3: 실패하는 리포지토리 테스트 작성**

`BrandLinkRepositoryTest.java`에 추가 (기존 관용구 — `userId` 시드·brandA/brandB 사용):

```java
@Test
void 연결은_신청한_collection_months를_저장한다() {
	repository.insertLink(userId, brandA, "lizda_official", "own", 3);

	assertThat(repository.findActiveByUserAndBrand(userId, brandA).orElseThrow().collectionMonths())
			.isEqualTo(3);
}

@Test
void updateCollectionMonths는_활성_연결만_갱신하고_없으면_false다() {
	repository.insertLink(userId, brandA, "lizda_official", "own", 12);

	assertThat(repository.updateCollectionMonths(userId, brandA, 3)).isTrue();
	assertThat(repository.findActiveByUserAndBrand(userId, brandA).orElseThrow().collectionMonths())
			.isEqualTo(3);
	// 활성 연결이 없는 브랜드는 false — 소유권 판정 신호(updateAccountType 동형).
	assertThat(repository.updateCollectionMonths(userId, brandB, 3)).isFalse();
}
```

같은 파일의 기존 `insertLink(userId, ..., "own")`/`"competitor"`/`"rival"` 4-인자 호출 전부에 다섯째 인자 `, 12`를 추가한다(9곳 — 47~114행 부근).

- [ ] **Step 4: 컴파일 실패 확인**

Run: `./gradlew :was:compileTestJava`
Expected: FAIL — `insertLink` 5-인자·`updateCollectionMonths`·`collectionMonths()` 미정의.

- [ ] **Step 5: 리포지토리·트랜잭션·서비스 구현**

`BrandLinkRepository.java`:

```java
private static final String SELECT_COLUMNS =
		"id, user_id, brand_id, username, account_type, collection_months, created_at, deleted_at";
```

```java
/** 활성 연결 생성. RETURNING id. 같은 (유저, 브랜드) 활성 연결이 있으면 DuplicateKeyException. */
public long insertLink(long userId, long brandId, String username, String accountType, int collectionMonths) {
	return jdbcClient.sql("""
			INSERT INTO app.brand_monitorings (user_id, brand_id, username, account_type, collection_months)
			VALUES (:userId, :brandId, :username, :accountType, :collectionMonths)
			RETURNING id
			""")
			.param("userId", userId)
			.param("brandId", brandId)
			.param("username", username)
			.param("accountType", accountType)
			.param("collectionMonths", collectionMonths)
			.query(Long.class)
			.single();
}
```

`updateAccountType` 바로 아래에 동형 메서드 추가:

```java
/**
 * 활성 연결의 표시 기간 변경(2026-08-17) — 재등록 요청이 명시한 신청값으로 그대로 갱신한다
 * (축소 허용 — 링크는 유저 개인 표시 범위라 자산의 max 규칙과 다르다). 반환값 의미는
 * {@link #updateAccountType}과 같다: false는 활성 연결 없음 하나뿐이다.
 */
public boolean updateCollectionMonths(long userId, long brandId, int collectionMonths) {
	return jdbcClient.sql("""
			UPDATE app.brand_monitorings SET collection_months = :collectionMonths
			WHERE user_id = :userId AND brand_id = :brandId AND deleted_at IS NULL
			""")
			.param("userId", userId)
			.param("brandId", brandId)
			.param("collectionMonths", collectionMonths)
			.update() > 0;
}
```

`BrandLinkTransaction.java` — `link`에 months 관통(두 곳):

```java
@Transactional
void link(long userId, long brandId, String username, String accountType, int collectionMonths) {
```

insert 줄:

```java
linkRepository.insertLink(userId, brandId, username, accountType, collectionMonths);
```

(existing.isPresent() 멱등 분기는 손대지 않는다 — precheck가 "미연결"로 본 뒤의 극히 드문 경합 경로라, 여기서 months까지 덮으면 생략(null) 요청이 12로 리셋되는 부작용이 생긴다. 다음 명시 재등록이 바로잡는다.)

`V1BrandAccountService.java:102`:

```java
linkTransaction.link(userId, registered.brandId(), username, accountType, months);
```

- [ ] **Step 6: 나머지 테스트 컴파일 수정**

위 Files 목록의 `new BrandLinkRow(` 호출 전부: `accountType` 인자 다음(= `createdAt` 앞)에 `12` 추가. 예 (`V1BrandPostsControllerTest.link()`):

```java
private static BrandLinkRow link() {
	return new BrandLinkRow(1L, 7L, 100L, "lizda_official", BrandAccountType.OWN, 12,
			OffsetDateTime.parse("2026-08-01T00:00:00Z"), null);
}
```

`V1BrandAccountsControllerTest`의 insertLink 검증(184행)도 5-인자로:

```java
then(linkRepository).should().insertLink(7L, 100L, "lizda_official", BrandAccountType.OWN, 12);
```

같은 파일에 `insertLink` 4-인자 검증이 더 있으면(489행 이후 collectionMonths 테스트들 — `신규_등록은_collectionMonths를_monitoring에_전달한다` 등) 전부 5-인자로 맞춘다. 그 테스트들의 기대 months 값은 요청 본문의 값이다(예: `collectionMonths: 3` 요청이면 `insertLink(..., 3)`).

- [ ] **Step 7: 테스트 실행**

Run: `./gradlew :was:test --tests "com.celfit.was.monitoring.BrandLinkRepositoryTest" --tests "com.celfit.was.v1.brandmonitoring.*"`
Expected: PASS (통합 테스트라 도커 필요 — 실패 양상이 대량이면 DOCKER_HOST부터 확인)

- [ ] **Step 8: 커밋**

```bash
git add -A && git commit -m "feat(was): brand_monitorings.collection_months 신설 — 유저별 신청 기간 저장"
```

---

### Task 2: 쓰기 경로 — 재등록 갱신 + 계정 응답을 링크 값으로

재-POST가 명시한 신청값으로 링크를 갱신하고(축소 허용, 생략 시 불변), 계정 응답 `collectionMonths`를 자산 값 대신 링크 값으로 바꾼다.

**Files:**
- Modify: `was/src/main/java/com/celfit/was/v1/brandmonitoring/V1BrandAccountService.java` (register·list·get)
- Modify: `was/src/main/java/com/celfit/was/v1/brandmonitoring/BrandAccountAssembler.java:47-69`
- Modify: `was/src/main/java/com/celfit/was/v1/brandmonitoring/BrandAccountResponse.java` (javadoc만)
- Test: `was/src/test/java/com/celfit/was/v1/brandmonitoring/V1BrandAccountsControllerTest.java`
- Test(컴파일): `was/src/test/java/com/celfit/was/v1/brandmonitoring/BrandAccountAssemblerTest.java` (toResponse 4곳에 셋째 인자 추가)

**Interfaces:**
- Consumes: Task 1의 `BrandLinkRow.collectionMonths()`, `updateCollectionMonths(...)`
- Produces: `BrandAccountAssembler.toResponse(BrandAccountRow row, String accountType, int collectionMonths)` — 응답의 collectionMonths는 이 파라미터(링크 값)

- [ ] **Step 1: 실패하는 테스트 작성**

`V1BrandAccountsControllerTest`에서:

먼저 link 헬퍼에 months 오버로드 추가(기존 4-인자 헬퍼는 12로 위임하도록):

```java
private static BrandLinkRow link(long userId, long brandId, String username, String accountType) {
	return link(userId, brandId, username, accountType, 12);
}

private static BrandLinkRow link(long userId, long brandId, String username, String accountType, int months) {
	return new BrandLinkRow(brandId, userId, brandId, username, accountType, months,
			OffsetDateTime.parse("2026-08-07T00:00:00Z"), null);
}
```

기존 테스트 `재등록으로_작은_값을_보내도_...collectionMonths...12` 계열(569행 부근 — 응답이 자산 값 12를 유지하던 테스트)을 **아래로 교체**하고, "자산 값 그대로" 테스트(799행 부근)도 링크 값 계약으로 교체한다:

```java
@Test
void 재등록이_명시한_collectionMonths는_링크에_그대로_반영된다_축소_허용() throws Exception {
	// 이미 연결됨(자산 12) + 3개월 재-POST → 자산은 불변(monitoring 콜 0), 링크만 3으로 갱신.
	given(linkRepository.findAllActiveByUser(7L)).willReturn(List.of(link(7L, 100L)));
	given(linkRepository.findActiveByUserAndBrand(7L, 100L))
			.willReturn(Optional.of(link(7L, 100L, "lizda_official", BrandAccountType.OWN, 3)));
	given(brandReadRepository.findAccount(100L)).willReturn(Optional.of(readyRow(100L)));

	mockMvc.perform(post("/v1/brand-monitoring/accounts").with(user(principal())).with(csrf())
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"username\": \"lizda_official\", \"collectionMonths\": 3}"))
			.andExpect(status().isAccepted())
			.andExpect(jsonPath("$.data.collectionMonths").value(3));   // 링크 값 — 자산(12) 아님

	then(linkRepository).should().updateCollectionMonths(7L, 100L, 3);
	then(commandClient).should(never()).registerBrand(anyString(), any(), anyInt());
}

@Test
void 재등록이_collectionMonths를_생략하면_링크_기간은_불변이다() throws Exception {
	// 구 클라이언트의 필드 없는 재-POST가 3개월 링크를 12로 되돌리면 안 된다.
	given(linkRepository.findAllActiveByUser(7L)).willReturn(List.of(link(7L, 100L)));
	given(linkRepository.findActiveByUserAndBrand(7L, 100L))
			.willReturn(Optional.of(link(7L, 100L, "lizda_official", BrandAccountType.OWN, 3)));
	given(brandReadRepository.findAccount(100L)).willReturn(Optional.of(readyRow(100L)));

	mockMvc.perform(post("/v1/brand-monitoring/accounts").with(user(principal())).with(csrf())
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"username\": \"lizda_official\"}"))
			.andExpect(status().isAccepted());

	then(linkRepository).should(never()).updateCollectionMonths(anyLong(), anyLong(), anyInt());
}

@Test
void 응답의_collectionMonths는_자산이_아니라_링크_값이다() throws Exception {
	// 자산은 12(다른 유저의 max)지만 이 유저 신청은 3 — 응답은 유저 신청값.
	given(linkRepository.findActiveByUserAndBrand(7L, 100L))
			.willReturn(Optional.of(link(7L, 100L, "lizda_official", BrandAccountType.OWN, 3)));
	given(brandReadRepository.findAccount(100L)).willReturn(Optional.of(readyRow(100L)));

	mockMvc.perform(get("/v1/brand-monitoring/accounts/100").with(user(principal())))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.collectionMonths").value(3));
}
```

주의: 기존 `응답은_자산의_collectionMonths를_그대로_싣는다`(799행 부근)는 삭제하고 위 테스트가 대체한다. 기존 확장 게이트 테스트(요청 12 > 자산 6 → registerBrand 재호출)는 **그대로 유지** — 그 테스트가 이제 `updateCollectionMonths(7L, 100L, 12)` 호출도 겸하게 되므로 검증이 깨지면 should() 추가로 맞춘다.

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :was:test --tests "com.celfit.was.v1.brandmonitoring.V1BrandAccountsControllerTest"`
Expected: FAIL — updateCollectionMonths 미호출, collectionMonths가 12(자산 값).

- [ ] **Step 3: 구현**

`V1BrandAccountService.register` — alreadyLinked 분기(86~97행)에 링크 갱신 추가:

```java
Optional<Long> alreadyLinked = linkTransaction.precheck(userId, username, accountType);
if (alreadyLinked.isPresent()) {
	long brandId = alreadyLinked.get();
	// 기간 확장(스펙 §3) — 자산 창보다 클 때만 monitoring 재호출. 사전 게이트일 뿐 정본 판정은
	// monitoring replay가 한 번 더 한다(경합으로 게이트가 낡아도 결과는 같다). 자산은 축소 없음.
	if (months > findAccountOrThrow(brandId).collectionMonths()) {
		String expandBrandName = BrandAccountType.OWN.equals(accountType) ? brandNameOf(userId) : null;
		translate(() -> commandClient.registerBrand(username, expandBrandName, months));
	}
	// 링크(유저 표시 창, 2026-08-17)는 명시한 값으로 그대로 — 축소 허용. 생략(null)은 불변이다:
	// orDefault로 접힌 12를 쓰면 필드 없는 구 클라이언트 재-POST가 신청 기간을 12로 되돌린다.
	if (rawCollectionMonths != null) {
		linkRepository.updateCollectionMonths(userId, brandId, months);
	}
	return get(userId, brandId);
}
```

`register`의 javadoc 마지막 단락도 갱신: "collectionMonths는 자산 확장 게이트(max)와 링크 표시 창(그대로 설정) 두 곳에 반영된다" 취지로 한 줄.

`list`(131행)·`get`(152행) — toResponse에 링크 값 전달:

```java
accounts.add(assembler.toResponse(row.get(), link.accountType(), link.collectionMonths()));
```

```java
return assembler.toResponse(findAccountOrThrow(brandId), link.accountType(), link.collectionMonths());
```

`BrandAccountAssembler.toResponse` — 시그니처와 본문:

```java
public BrandAccountResponse toResponse(BrandAccountRow row, String accountType, int collectionMonths) {
```

69행 `row.collectionMonths()`를 파라미터로 교체:

```java
				// 표시 기간은 자산이 아니라 호출자가 쥔 연결 행에서 온다(2026-08-17) — 자산 값(유저 간
				// max)을 실으면 3개월 신청 유저의 FE 안내·잠금이 12개월로 표시된다.
				collectionMonths,
```

`BrandAccountResponse.java` 18행 javadoc 교체:

```java
 * <p>{@code collectionMonths}는 연결(유저) 레벨 신청값이다(2026-08-17) — 자산 값(유저 간 max)이
 * 아니라 이 유저가 등록 시 고른 표시 기간. 게시물 목록·counts도 같은 창으로 잘려 내려간다.
```

`BrandAccountAssemblerTest`의 `toResponse(` 4곳(39·49·58·65행)에 셋째 인자 `12` 추가.

- [ ] **Step 4: 테스트 실행**

Run: `./gradlew :was:test --tests "com.celfit.was.v1.brandmonitoring.*"`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add -A && git commit -m "feat(was): 재등록 신청값을 링크에 반영(축소 허용) + 계정 응답 collectionMonths를 링크 값으로"
```

---

### Task 3: 읽기 경로 — 게시물 목록·상세를 링크 창으로 자르기

게시물 목록·counts·상세를 링크의 `collection_months` 창(KST 달력일, direct 예외)으로 자른다. 컨트롤러에 `Clock`(기존 `ClockConfig` 빈)을 주입해 테스트가 시간을 고정한다 — 고정하지 않으면 기존 고정 날짜(2026-08-xx) 테스트 전체가 시한부가 된다.

**Files:**
- Modify: `was/src/main/java/com/celfit/was/v1/brandmonitoring/V1BrandPostsController.java`
- Test: `was/src/test/java/com/celfit/was/v1/brandmonitoring/V1BrandPostsControllerTest.java`

**Interfaces:**
- Consumes: Task 1의 `BrandLinkRow.collectionMonths()`; 기존 `BrandPostAssembler.uploadedOn(post)`(package-static)·`BrandPostAssembler.SOURCE_DIRECT`; 기존 `Clock` 빈(`ClockConfig`, systemUTC)
- Produces: (내부) `requireOwnership`이 `BrandLinkRow`를 반환

- [ ] **Step 1: 실패하는 테스트 작성**

`V1BrandPostsControllerTest`에 Clock 고정 배선 추가:

```java
import java.time.Clock;
import java.time.Instant;
```

```java
	@MockitoBean
	Clock clock;
```

`ownedBrand()` @BeforeEach에 추가(기존 given들 위):

```java
		// 링크 창 컷의 기준 시각 고정 — 고정하지 않으면 2026-08-xx 고정 날짜 데이터가 시간이 지나며
		// 창 밖으로 밀려 테스트 전체가 시한부가 된다. KST 2026-08-08 21:00.
		given(clock.instant()).willReturn(Instant.parse("2026-08-08T12:00:00Z"));
```

새 테스트 4개 (컷 = 2026-08-08 − months):

```java
	// ---------- 링크 표시 창(2026-08-17 스펙) ----------

	@Test
	void 링크_창_밖_tagged는_목록과_counts에서_빠진다() throws Exception {
		// 자산은 12개월치를 들고 있어도(BBB: 4개월 전) 3개월 신청 유저에겐 창 안(AAA)만 보인다.
		given(linkRepository.findActiveByUserAndBrand(7L, 100L))
				.willReturn(Optional.of(linkWithMonths(3)));
		givenTagged(taggedRow("AAA", "2026-08-06T01:00:00Z"), taggedRow("BBB", "2026-04-01T01:00:00Z"));
		given(brandReadRepository.findPostMeta(any())).willReturn(List.of(
				meta("AAA", "REELS", null), meta("BBB", "FEED", null)));

		mockMvc.perform(get("/v1/brand-monitoring/accounts/100/posts").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.length()").value(1))
				.andExpect(jsonPath("$.data[0].shortcode").value("AAA"))
				// counts도 자른 전량 기준 — 탭 뱃지가 유저 창과 일치해야 한다.
				.andExpect(jsonPath("$.meta.counts.all").value(1))
				.andExpect(jsonPath("$.meta.counts.tagged").value(1));
	}

	@Test
	void 링크_창_경계일은_포함이다() throws Exception {
		// 컷 = 2026-08-08(KST 고정) − 3개월 = 2026-05-08. 그 날짜 업로드(KST 10시)는 포함.
		given(linkRepository.findActiveByUserAndBrand(7L, 100L))
				.willReturn(Optional.of(linkWithMonths(3)));
		givenTagged(taggedRow("EDG", "2026-05-08T01:00:00Z"));
		given(brandReadRepository.findPostMeta(any())).willReturn(List.of(meta("EDG", "REELS", null)));

		mockMvc.perform(get("/v1/brand-monitoring/accounts/100/posts").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.length()").value(1));
	}

	@Test
	void direct는_링크_창_밖이어도_포함이다() throws Exception {
		// 직접 등록은 유저가 URL을 명시한 추적 대상 — 창은 태그 수집 범위의 개념이라 적용하지 않는다.
		// direct 업로드일(2026-02-01)은 1개월 창(컷 2026-07-08) 한참 밖 — 예외 규칙이 실제로 판정을
		// 우회하는지 검증한다(창 안 날짜면 예외 없이도 통과해 테스트가 아무것도 못 잡는다).
		given(linkRepository.findActiveByUserAndBrand(7L, 100L))
				.willReturn(Optional.of(linkWithMonths(1)));
		givenTagged(taggedRow("AAA", "2026-04-01T01:00:00Z"));   // 창 밖 tagged — 제외 대조군
		given(brandReadRepository.findPostMeta(any())).willReturn(List.of(meta("AAA", "REELS", null)));
		givenDirect("XYZ", 42L, "2026-02-01");

		mockMvc.perform(get("/v1/brand-monitoring/accounts/100/posts").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.length()").value(1))
				.andExpect(jsonPath("$.data[0].source").value("direct"));
	}

	@Test
	void 링크_창_밖_게시물_상세는_404다() throws Exception {
		// 목록에 없는 게시물이 상세로는 열리는 불일치 방지 — 상세도 같은 창이다.
		given(linkRepository.findAllActiveByUser(7L)).willReturn(List.of(linkWithMonths(3)));
		givenTagged(taggedRow("OLD", "2026-04-01T01:00:00Z"));
		given(brandReadRepository.findPostMeta(any())).willReturn(List.of(meta("OLD", "REELS", null)));

		mockMvc.perform(get("/v1/brand-monitoring/posts/OLD").with(user(principal())))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
	}
```

헬퍼 추가(기존 `link()` 옆):

```java
	private static BrandLinkRow linkWithMonths(int months) {
		return new BrandLinkRow(1L, 7L, 100L, "lizda_official", BrandAccountType.OWN, months,
				OffsetDateTime.parse("2026-08-01T00:00:00Z"), null);
	}
```

기존 `givenDirect(String, long)`는 업로드일이 `"2026-08-06"`으로 하드코딩돼 있다 — 날짜 파라미터를 관통시키는 오버로드로 바꾸고 기존 2-인자는 위임으로 유지한다:

```java
	/** 직접 등록 1건 — 매핑 행 + 레거시 조립 결과를 함께 스텁한다. */
	private void givenDirect(String shortCode, long itemId) {
		givenDirect(shortCode, itemId, "2026-08-06");
	}

	private void givenDirect(String shortCode, long itemId, String uploadedDate) {
		given(directPostRepository.findByUser(7L))
				.willReturn(List.of(new BrandDirectPostRepository.Row(7L, 100L, shortCode, itemId)));
		var snapshot = new TrackingItemResponse.SnapshotResponse(uploadedDate, 300L, 20L, false, 9L,
				4L, 2L, false, 1L);
		var post = new TrackingItemResponse.TrackedPostResponse("https://www.instagram.com/reel/" + shortCode + "/",
				"reels", uploadedDate, "일상 기록", List.of(), "https://cdn/legacy-thumb.jpg", null,
				List.of(snapshot), List.of());
		var item = TrackingItemResponse.full(itemId, "url", "tracking", "glowdeep_92", "글로우딥",
				"https://cdn/author.jpg", 12345L, uploadedDate, null, null,
				"https://www.instagram.com/reel/" + shortCode + "/", LocalDate.of(2026, 8, 1), 30, null, post, null);
		given(trackingItemAssembler.assembleList(7L)).willReturn(new TrackingItemAssembler.AssembledList(
				List.of(item), OffsetDateTime.parse("2026-08-07T17:00:00Z"), LocalDate.of(2026, 8, 8)));
	}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :was:test --tests "com.celfit.was.v1.brandmonitoring.V1BrandPostsControllerTest"`
Expected: FAIL — 새 테스트 4개(창 미적용으로 2건 반환·상세 200), 그리고 Clock 빈 관련 컴파일/배선은 통과(@MockitoBean이 슬라이스에 빈을 공급).

- [ ] **Step 3: 구현**

`V1BrandPostsController.java`:

임포트 추가:

```java
import java.time.Clock;
```

필드·생성자에 Clock 추가:

```java
	private final Clock clock;

	public V1BrandPostsController(BrandLinkRepository linkRepository, BrandReadRepository brandReadRepository,
			BrandPostAssembler assembler, V1BrandDirectPostService directPostService,
			BrandHashtagPostAssembler hashtagPostAssembler, Clock clock) {
		...
		this.clock = clock;
	}
```

`requireOwnership` 반환형 변경(288행 부근):

```java
	private BrandLinkRow requireOwnership(long userId, long brandId) {
		return linkRepository.findActiveByUserAndBrand(userId, brandId)
				.orElseThrow(() -> V1ApiException.forbidden("FORBIDDEN", "브랜드 계정을 찾을 수 없거나 접근 권한이 없어요."));
	}
```

`list`(90~111행) — 전량 조립 직후 링크 창으로 자른다:

```java
		long brandId = parseAccountId(accountId);
		BrandLinkRow link = requireOwnership(principal.getUserId(), brandId);
		BrandAccountRow account = findAccountOrThrow(brandId);
		...
		// 유저 표시 창(2026-08-17) — 자산(brand_account)은 유저 간 max로 수집하므로 12개월치가
		// 있어도, 이 유저가 신청한 기간까지만 서빙한다. counts·필터·정렬 전부 자른 전량 기준.
		List<BrandPostResponse> all = assembler.assembleForBrand(principal.getUserId(), account).stream()
				.filter(p -> withinLinkWindow(p, linkWindowStart(link.collectionMonths())))
				.toList();
```

(이후 `filtered` 파이프라인·`meta(filtered.size(), all, account)`는 무변경 — `all`이 이미 잘려 있다.)

`hashtagPosts`·`registerDirectPosts` 쪽 `requireOwnership` 호출은 반환값을 버리면 되므로 무변경.

`get` 상세(135~148행) — 순회 중 같은 창 적용:

```java
		for (BrandLinkRow link : linkRepository.findAllActiveByUser(principal.getUserId())) {
			Optional<BrandAccountRow> account = brandReadRepository.findAccount(link.brandId());
			if (account.isEmpty()) {
				continue;
			}
			LocalDate windowStart = linkWindowStart(link.collectionMonths());
			Optional<BrandPostResponse> found = assembler.assembleForBrand(principal.getUserId(), account.get())
					.stream()
					// 창 밖 게시물은 목록에 없다 — 상세만 열리는 불일치를 만들지 않는다(같은 404).
					.filter(p -> withinLinkWindow(p, windowStart))
					.filter(p -> p.id().equals(postId))
					.findFirst();
			...
```

필터·정렬 섹션에 프라이빗 메서드 2개 추가(`withinUploadWindow` 옆):

```java
	/** 링크 표시 창의 하한 — KST 달력일 기준(windowCutoff 관용구 동형: 인스턴트 빼기는 경계가 흔들린다). */
	private LocalDate linkWindowStart(int collectionMonths) {
		return LocalDate.ofInstant(clock.instant(), KstTimestamps.KST).minusMonths(collectionMonths);
	}

	/**
	 * 링크 창 판정(2026-08-17) — direct는 유저가 URL을 명시 등록한 추적 대상이라 창과 무관하게
	 * 통과한다(창은 태그 수집 범위의 개념). tagged의 업로드일 미상은 제외 — withinUploadWindow의
	 * "판정 불가 제외" 규칙과 같고, 수집 구조상 tagged는 업로드일이 거의 항상 있다.
	 */
	private static boolean withinLinkWindow(BrandPostResponse post, LocalDate windowStart) {
		if (BrandPostAssembler.SOURCE_DIRECT.equals(post.source())) {
			return true;
		}
		LocalDate uploadedOn = BrandPostAssembler.uploadedOn(post);
		return uploadedOn != null && !uploadedOn.isBefore(windowStart);
	}
```

클래스 javadoc(37~39행 "필터·정렬은 전부 메모리다" 단락)에 한 줄 추가: counts는 "필터 적용 전 전량"이되 그 전량 자체가 링크 표시 창(2026-08-17)으로 잘린 뒤라는 것.

- [ ] **Step 4: 테스트 실행**

Run: `./gradlew :was:test --tests "com.celfit.was.v1.brandmonitoring.V1BrandPostsControllerTest"`
Expected: PASS — 새 4개 포함 전체. 기존 테스트는 링크 기본 12 + 고정 Clock(2026-08-08)으로 전부 창 안.

- [ ] **Step 5: was 모듈 전체 테스트**

Run: `./gradlew :was:test`
Expected: PASS (다른 컨트롤러 슬라이스에 Clock 빈 부재로 깨지는 게 없는지 — V1BrandPostsController를 로드하는 테스트는 이 파일뿐이어야 한다. 깨지면 해당 테스트에도 `@MockitoBean Clock` 추가)

- [ ] **Step 6: 커밋**

```bash
git add -A && git commit -m "feat(was): 게시물 목록·상세를 링크 표시 창으로 서빙(direct 예외, counts 동일 창)"
```

---

### Task 4: 문서·마무리 — DECISIONS·스펙 상태·plan 아카이브·PR

**Files:**
- Modify: `DECISIONS.md` (맨 위에 새 행)
- Modify: `docs/superpowers/specs/2026-08-17-brand-link-collection-months-design.md` (상태 헤더)
- Move: `docs/superpowers/plans/2026-08-17-brand-link-collection-months.md` → `docs/superpowers/plans/archive/`

- [ ] **Step 1: DECISIONS.md 맨 위에 결정 추가**

표 맨 위 행으로:

```markdown
| 2026-08-17 | **유저별 표시 기간은 링크 레벨 `brand_monitorings.collection_months` — 자산 max 위의 개인 창** — 08-12 "자산 레벨 max, 구독 레벨 관리 기각"을 부분 뒤집음: 여러 유저가 공유하는 브랜드(cclime 실사례)에서 3개월 신청 유저가 12개월치 전량을 받는 문제. 크롤 자산(유저 간 max, 축소 없음)은 불변이고, 링크에 신청값을 저장해 게시물 목록·counts·상세·계정 응답을 그 창으로 자른다(direct 게시물은 명시 등록이라 예외). 재등록 명시값은 그대로 반영(링크는 축소 허용 — 자산과 규칙이 다름), 생략은 불변. 기존 행 백필 12 + cclime 3개월 유저만 운영 수동 UPDATE(신청값이 영속화된 적 없어 복원 불가) | [설계](docs/superpowers/specs/2026-08-17-brand-link-collection-months-design.md) · 마이그레이션 `V<채번>__brand_monitorings_collection_months.sql`(app) · was `BrandLinkRow`·`BrandLinkRepository`·`V1BrandAccountService`·`BrandAccountAssembler`·`V1BrandPostsController`(Clock 주입) |
```

(`V<채번>`은 Task 1에서 실제 채번한 파일명으로 치환.)

- [ ] **Step 2: 스펙 상태 헤더 갱신 + plan 아카이브**

스펙 첫머리 상태를 `> 상태: 🟢 활성 · ✅ 구현됨 (2026-08-17)`으로 갱신:

```bash
git mv docs/superpowers/plans/2026-08-17-brand-link-collection-months.md docs/superpowers/plans/archive/
```

- [ ] **Step 3: 최종 검증 — 전체 테스트(PR 직전 1회)**

Run: `./gradlew test`
Expected: PASS (모듈 4개 병렬 — colima/도커 자원 확인)

- [ ] **Step 4: 커밋 + PR**

```bash
git add -A && git commit -m "docs: 링크 레벨 collection_months 결정 기록 + 스펙 상태 갱신 + plan 아카이브"
git push -u origin feature/cclime-subscription-period-filter-92c391
```

`gh pr create` — base `develop`, 제목 `feat(was): 유저별 신청 기간(링크 레벨 collection_months)으로 브랜드 게시물 서빙 창 분리`. 본문에 반드시 포함:

1. **FE 영향**: 계정 응답 `collectionMonths`가 자산 값 → 링크 값(3개월 신청 유저는 12 → 3), 게시물 목록·counts가 링크 창으로 축소. 요청 계약 불변.
2. **배포 후 운영 수동 보정 1회** (cclime 3개월 신청 유저 — 실행 전 대상 확인):

```sql
-- 대상 확인
SELECT bm.id, bm.user_id, u.email, bm.brand_id, bm.collection_months
  FROM app.brand_monitorings bm JOIN app.users u ON u.id = bm.user_id
 WHERE bm.username = 'cclime.beauty' AND bm.deleted_at IS NULL;
-- 3개월 신청 유저의 행만
UPDATE app.brand_monitorings SET collection_months = 3 WHERE id = <위에서 확인한 링크 id>;
```

3. PR 본문 끝: `🤖 Generated with [Claude Code](https://claude.com/claude-code)`
