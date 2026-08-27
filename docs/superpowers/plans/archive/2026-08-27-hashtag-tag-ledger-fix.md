# 해시태그 태그 장부 갭 수정 Implementation Plan

> 상태: ✅ 구현됨 · 2026-08-27 작성·구현

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 브랜드 링크를 가진 모든 사용자의 태그 원장(`app.brand_hashtag_tags`)에 최소 1개(계정명 유도) 태그가 항상 존재하도록 만들어, 해시태그 게시물의 사용자 격리 필터가 fail-open 없이 성립할 수 있게 한다.

**Architecture:** was가 monitoring `BrandHashtagTags.derive`(계정명 → IG 해시태그 유효 접두사)와 같은 규칙을 복제해, ①신규 브랜드 링크 생성 직후 그 사용자의 장부에 계정명 유도 태그를 멱등 삽입하고 ②기존 활성 링크 전원분을 Flyway 백필로 채운다. 동시에 `ensureSeeded`의 "장부가 완전히 비었을 때 monitoring 태그 전체 승계"를 "아무 사용자에게도 귀속되지 않은 태그만 승계"(diff)로 바꿔, 백필 이후 승계가 영영 발동하지 않아 무주 태그가 PUT 합집합에서 누락·삭제되는 회귀를 막는다.

**Tech Stack:** Java 21 · Spring Boot 4.1 · Gradle 멀티모듈(was) · JdbcClient · Flyway(`was/src/main/resources/db/migration/app`) · JUnit 5 + Mockito(MockitoExtension) + AssertJ · Testcontainers 2.x(PostgreSQL)

---

## 전제·주의 (실행 전에 반드시 읽을 것)

- **이 계획은 단독 배포 가능하다.** `docs/superpowers/plans/2026-08-27-hashtag-direct-collection.md`(계획 2)의 전제 조건이며, 계획 2보다 **먼저** 완주해야 한다.
- **테스트 실행 전 매 셸에서 아래를 export한다.** 미설정 시 Testcontainers가 colima 소켓을 못 찾아 통합 테스트가 무더기로 실패한다(테스트 결함으로 오진하기 쉬운 실패 양상).
  ```bash
  export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock
  ```
- **테스트는 모듈 단위로만 돌린다** — `./gradlew :was:test`. 전체 `./gradlew test`는 PR 직전에만.
- **스펙과 코드가 갈리는 지점(의도된 판단 — 되돌리지 말 것):** 스펙 §4는 "계정명 유도 태그(+own이면 브랜드명 유도 태그)"라고 쓰여 있지만, monitoring `BrandRegistrationService.seedHashtagsSafely`는 **2026-08-17 축소 이후 brandName을 시드에 전혀 쓰지 않는다**(파라미터만 하위 호환으로 남아 있고 `BrandHashtagTags.derive(username)` 1종만 심는다). was 장부가 monitoring이 실제로 스윕하지 않는 브랜드명 태그를 갖게 되면 ①사용자에게 "수집되지 않는 태그"가 보이고 ②다음 PUT의 합집합 계산이 그 태그를 monitoring에 밀어 넣어 08-17 결정(3종→1종)을 조용히 되돌린다. 따라서 이 계획은 **계정명 유도 1종만** 시드한다. 브랜드명 태그가 정말 필요하면 monitoring 시드부터 바꾸는 별도 트랙이다.
- 커밋 메시지는 한국어, prefix는 `feat(was):`/`fix(was):`/`test(was):`.

---

## File Structure

| 파일 | 책임 |
|---|---|
| `was/src/main/java/com/celfit/was/v1/brandmonitoring/BrandHashtagTags.java` | (신규) 계정명 → 해시태그 1종 유도. monitoring 동명 클래스의 규칙 복제. 순수 함수. |
| `was/src/test/java/com/celfit/was/v1/brandmonitoring/BrandHashtagTagsTest.java` | (신규) 위 유도 규칙 고정. |
| `was/src/main/java/com/celfit/was/v1/brandmonitoring/V1BrandAccountService.java` | (수정) 신규 링크 경로에 장부 시딩 훅 추가 + `ensureSeeded`를 diff 승계로 교체. |
| `was/src/main/java/com/celfit/was/monitoring/BrandHashtagTagRepository.java` | (수정) 더 이상 쓰이지 않는 `existsForBrand` 제거. |
| `was/src/main/resources/db/migration/app/V<UTC>__brand_hashtag_tags_backfill.sql` | (신규) 기존 활성 링크 전원 장부 백필. 단일 멱등 INSERT. |
| `was/src/test/java/com/celfit/was/monitoring/BrandHashtagTagsBackfillMigrationTest.java` | (신규) 백필 SQL을 classpath에서 읽어 재실행 — 유도 규칙·soft-delete 제외·멱등 검증. |
| `was/src/test/java/com/celfit/was/v1/brandmonitoring/V1BrandAccountServiceHashtagTagsTest.java` | (수정) 링크 생성 시딩 · diff 승계 테스트로 갱신. |
| `was/src/test/java/com/celfit/was/v1/brandmonitoring/V1BrandAccountsControllerTest.java` | (수정) `existsForBrand` 스텁 제거. |
| `was/src/test/java/com/celfit/was/monitoring/BrandHashtagTagRepositoryTest.java` | (수정) `existsForBrand` 테스트 제거. |

---

## Task 1: was 계정명 태그 유도(BrandHashtagTags)

**Files:**
- Create: `was/src/main/java/com/celfit/was/v1/brandmonitoring/BrandHashtagTags.java`
- Test: `was/src/test/java/com/celfit/was/v1/brandmonitoring/BrandHashtagTagsTest.java`

- [ ] **Step 1: 실패 테스트 작성**

`was/src/test/java/com/celfit/was/v1/brandmonitoring/BrandHashtagTagsTest.java`:

```java
package com.celfit.was.v1.brandmonitoring;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 계정명 해시태그 1종 유도(2026-08-27 태그 장부 갭 수정) — monitoring
 * {@code com.celfit.monitoring.service.BrandHashtagTags}의 규칙 복제본이라 케이스도 같은 것을 고정한다.
 * 두 벌이 갈리면 was 장부와 monitoring 스윕 대상이 어긋나 격리 필터가 조용히 빈 목록을 만든다.
 */
class BrandHashtagTagsTest {

	@Test
	void 계정명_그대로_소문자_태그_1종을_유도한다() {
		assertThat(BrandHashtagTags.derive("cclime_official")).containsExactly("cclime_official");
	}

	@Test
	void 대문자_섞인_계정명은_소문자_태그로_유도된다() {
		assertThat(BrandHashtagTags.derive("CClime_Official")).containsExactly("cclime_official");
	}

	@Test
	void 앞뒤_공백은_제거된다() {
		assertThat(BrandHashtagTags.derive("  cclime_official  ")).containsExactly("cclime_official");
	}

	@Test
	void 해시태그_불가_문자에서_잘린다() {
		// IG 해시태그는 점(.)에서 끊긴다 — 점 포함 계정명은 그 앞까지만 태그가 된다.
		assertThat(BrandHashtagTags.derive("cclime.beauty")).containsExactly("cclime");
	}

	@Test
	void 선행_유효_문자가_없으면_빈_집합이다() {
		assertThat(BrandHashtagTags.derive(".beauty")).isEmpty();
	}

	@Test
	void 한글_계정명도_유도된다() {
		assertThat(BrandHashtagTags.derive("끌리메")).containsExactly("끌리메");
	}
}
```

- [ ] **Step 2: 실패 확인**

```bash
export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock
./gradlew :was:test --tests "com.celfit.was.v1.brandmonitoring.BrandHashtagTagsTest"
```
Expected: 컴파일 실패 — `cannot find symbol: class BrandHashtagTags`

- [ ] **Step 3: 최소 구현**

`was/src/main/java/com/celfit/was/v1/brandmonitoring/BrandHashtagTags.java`:

```java
package com.celfit.was.v1.brandmonitoring;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 계정명 해시태그 1종 유도(2026-08-27 태그 장부 갭 수정) — monitoring
 * {@code com.celfit.monitoring.service.BrandHashtagTags#derive}의 <b>규칙 복제본</b>이다.
 * monitoring이 브랜드 등록·replay 때 {@code brand_hashtag}에 심는 자동 태그와 같은 값을 was의
 * 사용자 태그 원장({@code app.brand_hashtag_tags})에도 남기기 위해 필요하다 — 원장이 비면
 * 해시태그 게시물의 사용자 격리 필터(내 태그 ∩ 게시물 매칭 태그)가 아무것도 통과시키지 못한다.
 *
 * <p>모듈 간 Java 공유는 계약 모듈({@code contract-analysis})만 허용되므로 복제가 정본 관용구다
 * ({@code V1BrandAccountService.normalizeTag}가 monitoring {@code normalizeTagItem}을 복제한 것과 동형).
 * <b>규칙을 바꾸면 monitoring 쪽도 같이 바꿔야 한다</b> — 두 벌이 갈리면 장부와 스윕 대상이 어긋난다.
 *
 * <p>brandName(회사명) 유도는 하지 않는다 — monitoring이 2026-08-17에 자동 시드를 3종에서 계정명
 * 1종으로 축소하면서 brandName을 시드에서 뺐다. 여기서만 심으면 monitoring이 스윕하지 않는 태그가
 * 장부에 남고, 다음 PUT의 합집합 계산이 그 태그를 monitoring으로 되밀어 그 결정을 되돌린다.
 */
public final class BrandHashtagTags {

	/** IG 해시태그 허용 문자 — 글자(한글 포함)·숫자·언더스코어. 점(.)은 태그를 끊는다. */
	private static final Pattern VALID_TAG = Pattern.compile("[\\p{L}\\p{N}_]+");

	private BrandHashtagTags() {
	}

	/**
	 * 계정명 해시태그 1종(원소 0~1개) 유도 — username을 소문자·strip한 뒤 IG 해시태그 실동작(첫
	 * 무효 문자에서 잘림 — 예: cclime.beauty → cclime)에 맞춰 선행 유효 접두사만 취한다. 접두사가
	 * 없으면(무효 문자로 시작) 빈 집합을 반환한다.
	 *
	 * @param username 필수. null이면 NPE(등록 경로는 항상 정규화된 계정명을 넘긴다).
	 */
	public static LinkedHashSet<String> derive(String username) {
		String u = username.toLowerCase(Locale.ROOT).strip();
		LinkedHashSet<String> tags = new LinkedHashSet<>();
		String prefix = leadingValidPrefix(u);
		if (!prefix.isBlank()) {
			tags.add(prefix);
		}
		return tags;
	}

	/** 문자열 시작 지점부터 이어지는 최장 유효 해시태그 문자 구간(없으면 빈 문자열). */
	private static String leadingValidPrefix(String s) {
		Matcher m = VALID_TAG.matcher(s);
		return m.lookingAt() ? m.group() : "";
	}
}
```

- [ ] **Step 4: 통과 확인**

```bash
export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock
./gradlew :was:test --tests "com.celfit.was.v1.brandmonitoring.BrandHashtagTagsTest"
```
Expected: PASS (6 tests)

- [ ] **Step 5: 커밋**

```bash
git add was/src/main/java/com/celfit/was/v1/brandmonitoring/BrandHashtagTags.java \
        was/src/test/java/com/celfit/was/v1/brandmonitoring/BrandHashtagTagsTest.java
git commit -m "feat(was): 계정명 해시태그 유도 규칙 복제 - 태그 장부 시딩 재료"
```

---

## Task 2: 신규 링크 생성 시 장부 시딩

**Files:**
- Modify: `was/src/main/java/com/celfit/was/v1/brandmonitoring/V1BrandAccountService.java:124-138`(신규 링크 경로), 새 private 메서드 추가
- Test: `was/src/test/java/com/celfit/was/v1/brandmonitoring/V1BrandAccountServiceHashtagTagsTest.java`

- [ ] **Step 1: 실패 테스트 작성**

`V1BrandAccountServiceHashtagTagsTest.java`의 import 블록에 다음 4줄을 추가한다(기존 import 정렬 위치 유지 — `anyString` 아래에 `anyInt`, `MonitoringCommandClient` 아래에 nested record는 정규화된 이름으로 참조하므로 추가 import 불필요).

old_string:
```java
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
```
new_string:
```java
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
```

그리고 `// ---------- 조회 ----------` 섹션 <b>앞</b>에 아래 섹션을 삽입한다.

old_string:
```java
	// ---------- 조회 ----------
```
new_string:
```java
	// ---------- 링크 생성 시딩(2026-08-27 태그 장부 갭 수정 §4) ----------

	/**
	 * 신규 브랜드 링크를 만들면 그 사용자의 장부에 monitoring 자동 시드와 같은 계정명 유도 태그가
	 * 남아야 한다 — 남지 않으면 해시태그 게시물 격리 필터(내 태그 ∩ 매칭 태그)가 이 사용자에게
	 * 아무것도 통과시키지 못한다(08-27 진단된 갭).
	 */
	@Test
	void 신규_링크_생성은_계정명_유도_태그를_장부에_시딩한다() {
		given(commandClient.registerBrand(USERNAME, null, 12, BrandAccountType.OWN))
				.willReturn(new MonitoringCommandClient.BrandRegisterResult(BRAND_ID, USERNAME, 100L, "ACTIVE"));

		service.register(USER_ID, USERNAME, BrandAccountType.OWN, 12);

		then(hashtagTagRepository).should().addTags(USER_ID, BRAND_ID, List.of(USERNAME));
	}

	/** 멱등 재-POST(이미 연결된 브랜드)는 시딩하지 않는다 — 사용자가 지운 태그가 되살아나면 안 된다. */
	@Test
	void 멱등_재_POST는_장부를_시딩하지_않는다() {
		given(linkRepository.findAllActiveByUser(USER_ID)).willReturn(List.of(link()));

		service.register(USER_ID, USERNAME, BrandAccountType.OWN, null);

		then(hashtagTagRepository).should(never()).addTags(anyLong(), anyLong(), any());
		then(commandClient).should(never()).registerBrand(anyString(), any(), anyInt(), anyString());
	}

	/** 계정명이 무효 문자로 시작해 유도 태그가 0개면 원장 호출 자체가 없다(빈 목록 삽입 금지). */
	@Test
	void 유도_태그가_없으면_시딩을_건너뛴다() {
		given(commandClient.registerBrand(".beauty", null, 12, BrandAccountType.OWN))
				.willReturn(new MonitoringCommandClient.BrandRegisterResult(BRAND_ID, ".beauty", 100L, "ACTIVE"));

		service.register(USER_ID, ".beauty", BrandAccountType.OWN, 12);

		then(hashtagTagRepository).should(never()).addTags(anyLong(), anyLong(), any());
	}

	// ---------- 조회 ----------
```

- [ ] **Step 2: 실패 확인**

```bash
export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock
./gradlew :was:test --tests "com.celfit.was.v1.brandmonitoring.V1BrandAccountServiceHashtagTagsTest"
```
Expected: FAIL — `신규_링크_생성은_계정명_유도_태그를_장부에_시딩한다`가 `Wanted but not invoked: hashtagTagRepository.addTags(7L, 100L, [lizda_official])` (나머지 2건은 PASS)

- [ ] **Step 3: 최소 구현**

`V1BrandAccountService.java`의 신규 링크 경로 끝에 시딩 훅을 건다.

old_string:
```java
		} catch (RuntimeException e) {
			compensate(registered.brandId(), username);
			throw e;
		}
		// 등록 응답의 status는 monitoring이 "ACTIVE"로 하드코딩해 보내므로 준비 상태 판정에 쓸 수 없다 —
		// 상태는 항상 brand_account 조회가 정본이다(§5-2).
		return get(userId, registered.brandId());
	}
```
new_string:
```java
		} catch (RuntimeException e) {
			compensate(registered.brandId(), username);
			throw e;
		}
		// 태그 장부 시딩(2026-08-27 해시태그 직접 수집 설계 §4) — 신규 링크에만 건다. 멱등 재-POST는
		// 위 alreadyLinked 분기에서 이미 반환됐으므로 여기 도달하지 않는다(지운 태그 부활 방지).
		seedLedgerTagsSafely(userId, registered.brandId(), username);
		// 등록 응답의 status는 monitoring이 "ACTIVE"로 하드코딩해 보내므로 준비 상태 판정에 쓸 수 없다 —
		// 상태는 항상 brand_account 조회가 정본이다(§5-2).
		return get(userId, registered.brandId());
	}

	/**
	 * 신규 링크 장부 시딩(2026-08-27 해시태그 직접 수집 설계 §4) — monitoring
	 * {@code BrandRegistrationService.seedHashtagsSafely}가 {@code brand_hashtag}에 심는 계정명 유도
	 * 태그와 <b>같은 규칙</b>({@link BrandHashtagTags#derive})으로 이 사용자의 장부에도 같은 태그를
	 * 남긴다. 자동 등록 태그가 장부에 기록되지 않아 해시태그 격리 필터가 빈 교집합을 보던 갭
	 * (08-27 진단)의 수정이다. {@code addTags}는 ON CONFLICT DO NOTHING이라 재호출도 무해하다.
	 *
	 * <p>monitoring 쪽 시드와 같은 이유로 실패를 격리한다: 링크는 이미 커밋됐고, 여기서 던지면
	 * 재시도가 멱등 경로(시딩 없음)로 접혀 그 사용자의 장부가 <b>영구히</b> 비어 버린다. 시딩 실패의
	 * 실피해는 "이 사용자에게 해시태그 게시물이 안 보임"이고, 태그 관리 API로 직접 추가하면 복구된다.
	 */
	private void seedLedgerTagsSafely(long userId, long brandId, String username) {
		try {
			List<String> derived = List.copyOf(BrandHashtagTags.derive(username));
			if (!derived.isEmpty()) {
				hashtagTagRepository.addTags(userId, brandId, derived);
			}
		} catch (RuntimeException e) {
			log.warn("해시태그 태그 장부 시딩 실패(격리) — userId={}, brandId={}: {}", userId, brandId, e.toString());
		}
	}
```

- [ ] **Step 4: 통과 확인**

```bash
export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock
./gradlew :was:test --tests "com.celfit.was.v1.brandmonitoring.V1BrandAccountServiceHashtagTagsTest"
```
Expected: PASS (기존 테스트 포함 전량)

- [ ] **Step 5: 커밋**

```bash
git add was/src/main/java/com/celfit/was/v1/brandmonitoring/V1BrandAccountService.java \
        was/src/test/java/com/celfit/was/v1/brandmonitoring/V1BrandAccountServiceHashtagTagsTest.java
git commit -m "feat(was): 신규 브랜드 링크 생성 시 사용자 태그 장부에 계정명 태그 시딩"
```

---

## Task 3: 기존 활성 링크 전원 백필 마이그레이션

**Files:**
- Create: `was/src/main/resources/db/migration/app/V<UTC>__brand_hashtag_tags_backfill.sql`
- Test: `was/src/test/java/com/celfit/was/monitoring/BrandHashtagTagsBackfillMigrationTest.java`

> **채번:** 파일명의 `<UTC>`는 **실행 시점에** 아래 명령으로 뽑은 14자리 UTC 타임스탬프다. KST 채번 금지(미래 번호 선점 → 뒤따르는 정상 채번이 전부 Flyway out-of-order 거부에 빠진다).
> ```bash
> date -u +%Y%m%d%H%M%S
> ```
> 예를 들어 `20260827061500`이 나오면 파일명은 `V20260827061500__brand_hashtag_tags_backfill.sql`이다. was(app)는 monitoring과 **독립 버전 공간**이라 monitoring 마이그레이션 번호와 겹쳐도 무관하다.

- [ ] **Step 1: 실패 테스트 작성**

`was/src/test/java/com/celfit/was/monitoring/BrandHashtagTagsBackfillMigrationTest.java`:

```java
package com.celfit.was.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.was.IntegrationTest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 태그 장부 백필 마이그레이션(2026-08-27 해시태그 직접 수집 설계 §4) 검증 — 컨테이너 기동 시점의
 * DB는 비어 있어 마이그레이션이 no-op으로 지나가므로, <b>마이그레이션 파일 원문을 classpath에서
 * 읽어 다시 실행</b>해 검증한다(테스트가 SQL 사본을 들고 있으면 파일과 조용히 갈린다).
 * 파일명은 UTC 채번이라 글롭으로 찾는다 — 그래서 이 테스트는 채번 값과 무관하게 계속 유효하다.
 */
class BrandHashtagTagsBackfillMigrationTest extends IntegrationTest {

	@Autowired
	JdbcClient jdbcClient;
	@Autowired
	JdbcTemplate jdbcTemplate;
	@Autowired
	BrandHashtagTagRepository repository;

	long userId;
	long brandId;
	long deletedLinkBrandId;

	/** 백필 SQL 원문 — 마이그레이션 파일이 정확히 1개여야 한다(중복 채번 방지 겸용). */
	private static String backfillSql() throws IOException {
		Resource[] found = new PathMatchingResourcePatternResolver()
				.getResources("classpath*:db/migration/app/V*__brand_hashtag_tags_backfill.sql");
		assertThat(found).hasSize(1);
		return found[0].getContentAsString(StandardCharsets.UTF_8);
	}

	@BeforeEach
	void 링크_시드() {
		userId = jdbcClient.sql("INSERT INTO app.users (email, password_hash) VALUES (:email, 'x') RETURNING id")
				.param("email", "ledger-backfill-" + UUID.randomUUID() + "@test.io")
				.query(Long.class).single();
		// brand_id는 테스트마다 고유해야 한다 — 통합 테스트가 컨테이너를 공유하고 롤백이 없다.
		brandId = System.nanoTime();
		deletedLinkBrandId = brandId + 1;
	}

	private void insertLink(long linkBrandId, String username, boolean deleted) {
		jdbcClient.sql("""
				INSERT INTO app.brand_monitorings (user_id, brand_id, username, account_type, collection_months,
				                                   deleted_at)
				VALUES (:userId, :brandId, :username, 'own', 12, :deletedAt)
				""")
				.param("userId", userId)
				.param("brandId", linkBrandId)
				.param("username", username)
				.param("deletedAt", deleted ? java.time.OffsetDateTime.now() : null)
				.update();
	}

	@Test
	void 활성_링크의_계정명_유도_태그를_장부에_채운다() throws IOException {
		insertLink(brandId, "cclime_official", false);

		jdbcTemplate.execute(backfillSql());

		assertThat(repository.findByUserAndBrand(userId, brandId)).containsExactly("cclime_official");
	}

	/** IG 해시태그는 점(.)에서 끊긴다 — was BrandHashtagTags.derive와 같은 결과여야 한다. */
	@Test
	void 점_포함_계정명은_점_앞까지만_태그가_된다() throws IOException {
		insertLink(brandId, "cclime.beauty", false);

		jdbcTemplate.execute(backfillSql());

		assertThat(repository.findByUserAndBrand(userId, brandId)).containsExactly("cclime");
	}

	/** 선행 유효 문자가 없으면 태그가 없다 — 빈 문자열 태그를 심으면 안 된다. */
	@Test
	void 무효_문자로_시작하는_계정명은_태그를_만들지_않는다() throws IOException {
		insertLink(brandId, ".beauty", false);

		jdbcTemplate.execute(backfillSql());

		assertThat(repository.findByUserAndBrand(userId, brandId)).isEmpty();
	}

	/** 해제된 연결은 대상이 아니다 — 해제한 사용자의 장부를 되살리면 안 된다. */
	@Test
	void 해제된_링크는_백필하지_않는다() throws IOException {
		insertLink(deletedLinkBrandId, "gone_brand", true);

		jdbcTemplate.execute(backfillSql());

		assertThat(repository.findByUserAndBrand(userId, deletedLinkBrandId)).isEmpty();
	}

	/** 재실행 안전(ON CONFLICT DO NOTHING) — 운영 재적용·롤포워드에서 중복 키로 죽지 않는다. */
	@Test
	void 두_번_실행해도_멱등이다() throws IOException {
		insertLink(brandId, "cclime_official", false);

		jdbcTemplate.execute(backfillSql());
		jdbcTemplate.execute(backfillSql());

		assertThat(repository.findByUserAndBrand(userId, brandId)).containsExactly("cclime_official");
	}

	/** 사용자가 이미 갖고 있는 태그는 그대로 두고 유도 태그만 더한다. */
	@Test
	void 기존_사용자_태그를_덮어쓰지_않는다() throws IOException {
		insertLink(brandId, "cclime_official", false);
		repository.addTags(userId, brandId, java.util.List.of("끌리메"));

		jdbcTemplate.execute(backfillSql());

		assertThat(repository.findByUserAndBrand(userId, brandId))
				.containsExactlyInAnyOrder("끌리메", "cclime_official");
	}
}
```

- [ ] **Step 2: 실패 확인**

```bash
export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock
./gradlew :was:test --tests "com.celfit.was.monitoring.BrandHashtagTagsBackfillMigrationTest"
```
Expected: FAIL — 6건 전부 `backfillSql()`의 `assertThat(found).hasSize(1)`에서 `Expected size: 1 but was: 0` (마이그레이션 파일 없음)

- [ ] **Step 3: 최소 구현 — 마이그레이션 파일 생성**

파일명을 먼저 채번한다.

```bash
echo "was/src/main/resources/db/migration/app/V$(date -u +%Y%m%d%H%M%S)__brand_hashtag_tags_backfill.sql"
```

그 경로에 아래 내용을 그대로 쓴다.

```sql
-- 사용자 태그 장부 백필(2026-08-27 해시태그 직접 수집 설계 §4) — 기존 활성 링크 전원분.
--
-- 08-27 진단된 갭: 브랜드 등록 시 monitoring이 brand_hashtag에 자동으로 심는 계정명 유도 태그가
-- was의 사용자 태그 원장(app.brand_hashtag_tags)에는 기록되지 않는다. 해시태그 게시물의 사용자
-- 격리 필터는 "내 장부 태그 ∩ 게시물 매칭 태그"라, 장부가 비면 이 사용자에게 아무것도 보이지
-- 않는다(구 fail-open이 이걸 가리고 있었고, 그 fail-open은 이제 폐기된다).
--
-- 유도 규칙은 was BrandHashtagTags.derive / monitoring BrandHashtagTags.derive와 같다:
-- 소문자화 후 "선행 유효 해시태그 문자 구간"만 취한다(IG 해시태그는 점(.)에서 끊긴다).
-- 정규식을 [a-z0-9_]로 쓴 것은 축약이 아니라 정확한 대응이다 — username은 등록 시
-- BrandUsername.validate가 ^[a-z0-9._]{1,30}$로 이미 강제한 값이라 비ASCII가 들어올 수 없다.
-- substring(...)은 매치가 없으면 NULL을 돌려주므로 그 행은 WHERE에서 자연히 빠진다.
--
-- 해제된 연결(deleted_at IS NOT NULL)은 제외한다 — 사용자가 끊은 브랜드의 장부를 되살리면 안 된다.
-- ON CONFLICT DO NOTHING이라 재실행 안전하고, 사용자가 직접 관리 중인 기존 태그도 건드리지 않는다.
INSERT INTO app.brand_hashtag_tags (user_id, brand_id, tag)
SELECT bm.user_id,
       bm.brand_id,
       substring(lower(bm.username) from '^[a-z0-9_]+')
FROM app.brand_monitorings bm
WHERE bm.deleted_at IS NULL
  AND substring(lower(bm.username) from '^[a-z0-9_]+') IS NOT NULL
ON CONFLICT (user_id, brand_id, tag) DO NOTHING;
```

- [ ] **Step 4: 통과 확인**

```bash
export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock
./gradlew :was:test --tests "com.celfit.was.monitoring.BrandHashtagTagsBackfillMigrationTest"
```
Expected: PASS (6 tests)

- [ ] **Step 5: 커밋**

```bash
git add was/src/main/resources/db/migration/app/ \
        was/src/test/java/com/celfit/was/monitoring/BrandHashtagTagsBackfillMigrationTest.java
git commit -m "feat(was): 기존 활성 브랜드 링크 전원의 해시태그 태그 장부 백필"
```

---

## Task 4: ensureSeeded를 무주 태그 diff 승계로 교체

**Files:**
- Modify: `was/src/main/java/com/celfit/was/v1/brandmonitoring/V1BrandAccountService.java:294-310`(`ensureSeeded`)
- Modify: `was/src/main/java/com/celfit/was/monitoring/BrandHashtagTagRepository.java:49-64`(`existsForBrand` 제거)
- Test: `was/src/test/java/com/celfit/was/v1/brandmonitoring/V1BrandAccountServiceHashtagTagsTest.java`
- Test: `was/src/test/java/com/celfit/was/v1/brandmonitoring/V1BrandAccountsControllerTest.java`
- Test: `was/src/test/java/com/celfit/was/monitoring/BrandHashtagTagRepositoryTest.java`

> **왜 바꾸나:** 구 `ensureSeeded`는 "이 브랜드에 원장 행이 하나도 없을 때"만 monitoring 태그 전체를 승계했다. Task 3의 백필 이후에는 모든 브랜드에 원장 행이 생기므로 **이 승계가 영영 발동하지 않는다** — 그러면 격리 개정(08-19) 이전부터 monitoring에만 있던 무주 태그가 PUT 합집합 계산(`unionByBrand`)에서 계속 누락되고, PUT은 전체 교체 계약이라 그 태그가 monitoring에서 **삭제**된다. 승계 조건을 "아무 사용자에게도 귀속되지 않은 태그만"(diff)으로 바꾸면 백필 후에도 무주 태그가 최초 조작 사용자에게 귀속된다.
>
> **비용:** 태그 관리 쓰기 경로(PUT/POST/DELETE)마다 monitoring `GET hashtag-tags` 콜이 1회 항상 나간다(구 구조는 원장이 있으면 건너뛰었다). 태그 관리는 사람이 누르는 저빈도 조작이라 수용한다.

- [ ] **Step 1: 실패 테스트 작성 — 승계 규칙**

`V1BrandAccountServiceHashtagTagsTest.java`의 두 시딩 테스트를 diff 승계 테스트로 교체한다.

old_string:
```java
	/** 원장에 이 브랜드 행이 하나도 없으면(최초 조작) monitoring의 현재 태그를 이 유저에게 시딩한 뒤 진행한다. */
	@Test
	void putHashtagTags는_원장이_비어있으면_monitoring_현재_태그를_먼저_시딩한다() {
		given(hashtagTagRepository.existsForBrand(BRAND_ID)).willReturn(false);
		given(commandClient.getHashtagTags(USERNAME)).willReturn(List.of("레거시태그"));
		given(hashtagTagRepository.unionByBrand(BRAND_ID)).willReturn(Set.of());
		given(hashtagTagRepository.findByUserAndBrand(USER_ID, BRAND_ID)).willReturn(Set.of());

		service.putHashtagTags(USER_ID, BRAND_ID, List.of("새태그"));

		then(hashtagTagRepository).should().addTags(USER_ID, BRAND_ID, List.of("레거시태그"));
		then(hashtagTagRepository).should().replaceTags(USER_ID, BRAND_ID, List.of("새태그"));
	}

	@Test
	void putHashtagTags는_원장이_있으면_시딩을_생략한다() {
		given(hashtagTagRepository.existsForBrand(BRAND_ID)).willReturn(true);
		given(hashtagTagRepository.unionByBrand(BRAND_ID)).willReturn(Set.of());
		given(hashtagTagRepository.findByUserAndBrand(USER_ID, BRAND_ID)).willReturn(Set.of());

		service.putHashtagTags(USER_ID, BRAND_ID, List.of("새태그"));

		then(commandClient).should(never()).getHashtagTags(USERNAME);
	}
```
new_string:
```java
	// ---------- 무주 태그 승계(2026-08-27 diff 개정) ----------

	/**
	 * 백필(Task 3) 이후에는 모든 브랜드에 원장 행이 있으므로 구 "원장이 완전히 비었을 때만 전량
	 * 승계"는 영영 발동하지 않는다 — 그러면 격리 개정 이전부터 monitoring에만 있던 무주 태그가
	 * PUT 합집합에서 누락되고, PUT은 전체 교체 계약이라 monitoring에서 삭제된다.
	 * 승계 대상은 "아무 사용자에게도 귀속되지 않은 태그"뿐이다.
	 */
	@Test
	void addHashtagTags는_무주_태그만_조작_유저에게_승계한다() {
		given(commandClient.getHashtagTags(USERNAME)).willReturn(List.of("무주태그", "남의태그"));
		given(hashtagTagRepository.unionByBrand(BRAND_ID))
				.willReturn(new LinkedHashSet<>(List.of("남의태그")));

		service.addHashtagTags(USER_ID, BRAND_ID, List.of("새태그"));

		then(hashtagTagRepository).should().addTags(USER_ID, BRAND_ID, List.of("무주태그"));
		then(hashtagTagRepository).should().addTags(USER_ID, BRAND_ID, List.of("새태그"));
	}

	/** 이미 누군가에게 귀속된 태그만 있으면 승계는 없다 — 남의 태그를 내 것으로 만들면 안 된다. */
	@Test
	void addHashtagTags는_전부_귀속된_태그면_승계하지_않는다() {
		given(commandClient.getHashtagTags(USERNAME)).willReturn(List.of("남의태그"));
		given(hashtagTagRepository.unionByBrand(BRAND_ID))
				.willReturn(new LinkedHashSet<>(List.of("남의태그")));

		service.addHashtagTags(USER_ID, BRAND_ID, List.of("새태그"));

		then(hashtagTagRepository).should(never()).addTags(USER_ID, BRAND_ID, List.of("남의태그"));
		then(hashtagTagRepository).should().addTags(USER_ID, BRAND_ID, List.of("새태그"));
	}

	/** monitoring 태그가 아예 없으면 원장 조회조차 하지 않는다(불필요한 왕복 방지). */
	@Test
	void putHashtagTags는_monitoring_태그가_비면_승계하지_않는다() {
		given(commandClient.getHashtagTags(USERNAME)).willReturn(List.of());
		given(hashtagTagRepository.unionByBrand(BRAND_ID)).willReturn(Set.of());
		given(hashtagTagRepository.findByUserAndBrand(USER_ID, BRAND_ID)).willReturn(Set.of());

		service.putHashtagTags(USER_ID, BRAND_ID, List.of("새태그"));

		then(hashtagTagRepository).should(never()).addTags(anyLong(), anyLong(), any());
		then(hashtagTagRepository).should().replaceTags(USER_ID, BRAND_ID, List.of("새태그"));
	}
```

- [ ] **Step 2: 남은 `existsForBrand` 스텁 제거(같은 파일)**

`V1BrandAccountServiceHashtagTagsTest.java`에 남은 `existsForBrand` 스텁 8곳을 아래 치환으로 지운다(`replace_all` 금지 — 문맥과 함께 한 건씩 지운다).

1. old_string:
```java
		given(hashtagTagRepository.existsForBrand(BRAND_ID)).willReturn(true);   // 이미 시딩됨
		given(hashtagTagRepository.unionByBrand(BRAND_ID))
				.willReturn(new LinkedHashSet<>(List.of("공통태그", "내옛태그")));
```
new_string:
```java
		given(hashtagTagRepository.unionByBrand(BRAND_ID))
				.willReturn(new LinkedHashSet<>(List.of("공통태그", "내옛태그")));
```

2. old_string:
```java
		given(hashtagTagRepository.existsForBrand(BRAND_ID)).willReturn(true);
		given(hashtagTagRepository.unionByBrand(BRAND_ID)).willReturn(Set.of());
		given(hashtagTagRepository.findByUserAndBrand(USER_ID, BRAND_ID)).willReturn(Set.of());

		service.putHashtagTags(USER_ID, BRAND_ID, List.of(" #리즈다 ", "LIZDA", "리즈다"));
```
new_string:
```java
		given(hashtagTagRepository.unionByBrand(BRAND_ID)).willReturn(Set.of());
		given(hashtagTagRepository.findByUserAndBrand(USER_ID, BRAND_ID)).willReturn(Set.of());

		service.putHashtagTags(USER_ID, BRAND_ID, List.of(" #리즈다 ", "LIZDA", "리즈다"));
```

3. old_string:
```java
		given(hashtagTagRepository.existsForBrand(BRAND_ID)).willReturn(true);
		given(hashtagTagRepository.unionByBrand(BRAND_ID)).willReturn(Set.of());
		given(hashtagTagRepository.findByUserAndBrand(USER_ID, BRAND_ID)).willReturn(Set.of());

		service.putHashtagTags(USER_ID, BRAND_ID, null);
```
new_string:
```java
		given(hashtagTagRepository.unionByBrand(BRAND_ID)).willReturn(Set.of());
		given(hashtagTagRepository.findByUserAndBrand(USER_ID, BRAND_ID)).willReturn(Set.of());

		service.putHashtagTags(USER_ID, BRAND_ID, null);
```

4. old_string:
```java
		given(hashtagTagRepository.existsForBrand(BRAND_ID)).willReturn(true);

		service.addHashtagTags(USER_ID, BRAND_ID, List.of("새태그"));
```
new_string:
```java
		service.addHashtagTags(USER_ID, BRAND_ID, List.of("새태그"));
```

5. old_string:
```java
		then(hashtagTagRepository).should(never()).existsForBrand(anyLong());
		then(commandClient).should().addHashtagTags(USERNAME, List.of());
```
new_string:
```java
		then(commandClient).should(never()).getHashtagTags(anyString());
		then(commandClient).should().addHashtagTags(USERNAME, List.of());
```

6. old_string:
```java
		given(hashtagTagRepository.existsForBrand(BRAND_ID)).willReturn(true);
		given(hashtagTagRepository.hasOtherUserWithTag(BRAND_ID, "리즈다", USER_ID)).willReturn(true);
```
new_string:
```java
		given(hashtagTagRepository.hasOtherUserWithTag(BRAND_ID, "리즈다", USER_ID)).willReturn(true);
```

7. old_string:
```java
		given(hashtagTagRepository.existsForBrand(BRAND_ID)).willReturn(true);
		given(hashtagTagRepository.hasOtherUserWithTag(BRAND_ID, "리즈다", USER_ID)).willReturn(false);
```
new_string:
```java
		given(hashtagTagRepository.hasOtherUserWithTag(BRAND_ID, "리즈다", USER_ID)).willReturn(false);
```

8. old_string:
```java
		given(hashtagTagRepository.existsForBrand(BRAND_ID)).willReturn(true);
		given(hashtagTagRepository.findByUserAndBrand(USER_ID, BRAND_ID))
				.willReturn(new LinkedHashSet<>(List.of("공유태그", "내전용태그")));
```
new_string:
```java
		given(hashtagTagRepository.findByUserAndBrand(USER_ID, BRAND_ID))
				.willReturn(new LinkedHashSet<>(List.of("공유태그", "내전용태그")));
```

- [ ] **Step 3: 실패 확인**

```bash
export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock
./gradlew :was:test --tests "com.celfit.was.v1.brandmonitoring.V1BrandAccountServiceHashtagTagsTest"
```
Expected: FAIL — `addHashtagTags는_무주_태그만_조작_유저에게_승계한다`가 `Wanted but not invoked: addTags(7L, 100L, [무주태그])`, 그리고 `unionByBrand`/`getHashtagTags` 스텁이 안 쓰여 `UnnecessaryStubbingException`

- [ ] **Step 4: 최소 구현 — ensureSeeded 교체**

`V1BrandAccountService.java`:

old_string:
```java
	/**
	 * 최초 시딩(08-19) — 이 브랜드에 원장 행이 하나도 없으면(=이 기능 출시 이후 아직 아무도 태그
	 * 관리 API를 안 건드림) monitoring의 현재 태그 전체를 이 유저에게 귀속시킨다. 이 기능 출시
	 * 이전부터 있던 브랜드 단위 태그(자동 유도 계정명 태그 포함)는 원래 아무에게도 귀속돼 있지
	 * 않으므로, 최초로 태그 관리를 조작하는 유저가 물려받는다 — 그래야 이후 PUT·삭제의 합집합·
	 * "다른 소유자 없음" 판정이 기존 태그를 놓치지 않는다(별도 백필 마이그레이션 잡 불필요 —
	 * 오프라인 배치 대신 최초 쓰기 시점에 자연히 수렴).
	 */
	private void ensureSeeded(long userId, long brandId, String username) {
		if (hashtagTagRepository.existsForBrand(brandId)) {
			return;
		}
		List<String> current = commandClient.getHashtagTags(username);
		if (!current.isEmpty()) {
			hashtagTagRepository.addTags(userId, brandId, normalizeTags(current));
		}
	}
```
new_string:
```java
	/**
	 * 무주 태그 승계(08-19 최초 시딩 → <b>2026-08-27 diff 개정</b>) — monitoring의 브랜드 단위 태그
	 * 중 <b>아무 사용자에게도 귀속되지 않은 것만</b> 조작 사용자에게 귀속시킨다.
	 *
	 * <p>구 규칙("이 브랜드 원장이 완전히 비었으면 monitoring 태그 전체 승계")은 태그 장부 백필
	 * (2026-08-27 설계 §4) 이후 <b>영영 발동하지 않는다</b> — 모든 활성 링크에 원장 행이 생기기
	 * 때문이다. 그러면 격리 개정 이전부터 monitoring에만 있던 무주 태그가 {@link #putHashtagTags}의
	 * 합집합 계산에서 계속 빠지고, PUT은 전체 교체 계약이라 그 태그가 monitoring에서 삭제된다.
	 * 조건을 "원장 비었나"에서 "이 태그의 소유자가 있나"로 좁히면 백필 뒤에도 승계가 성립한다.
	 *
	 * <p>대가로 태그 관리 쓰기 경로마다 monitoring GET이 1콜 나간다(구 구조는 원장이 있으면
	 * 건너뛰었다) — 사람이 누르는 저빈도 조작이라 수용한다. monitoring 태그가 0건이면 원장 조회도
	 * 하지 않는다.
	 */
	private void ensureSeeded(long userId, long brandId, String username) {
		List<String> current = normalizeTags(commandClient.getHashtagTags(username));
		if (current.isEmpty()) {
			return;
		}
		Set<String> owned = hashtagTagRepository.unionByBrand(brandId);
		List<String> unowned = current.stream().filter(tag -> !owned.contains(tag)).toList();
		if (!unowned.isEmpty()) {
			hashtagTagRepository.addTags(userId, brandId, unowned);
		}
	}
```

- [ ] **Step 5: `existsForBrand` 제거**

`was/src/main/java/com/celfit/was/monitoring/BrandHashtagTagRepository.java`:

old_string:
```java
	/**
	 * 이 브랜드에 원장 행이 하나라도 있는지(최초 시딩 판정, {@code V1BrandAccountService#ensureSeeded}
	 * 전용) — 이 기능 출시 이전부터 monitoring이 이미 갖고 있던 브랜드 단위 태그는 아무 유저에게도
	 * 귀속돼 있지 않다. 원장이 완전히 비어 있으면(=이 브랜드에서 태그 관리 API를 아직 아무도 안 건드림)
	 * 최초 조작 유저가 monitoring의 현재 태그 전체를 물려받는다(정책 §정지조건 밖 — was 자체 완결
	 * 시딩, 백필 마이그레이션 잡 불필요).
	 */
	public boolean existsForBrand(long brandId) {
		Boolean exists = jdbcClient.sql("""
				SELECT EXISTS (SELECT 1 FROM app.brand_hashtag_tags WHERE brand_id = :brandId)
				""")
				.param("brandId", brandId)
				.query(Boolean.class)
				.single();
		return Boolean.TRUE.equals(exists);
	}

	/**
```
new_string:
```java
	/**
```

`was/src/test/java/com/celfit/was/monitoring/BrandHashtagTagRepositoryTest.java`에서 해당 테스트를 지운다.

old_string:
```java
	@Test
	void existsForBrand는_원장_행_유무를_판정한다() {
		assertThat(repository.existsForBrand(brandId)).isFalse();

		repository.addTags(userId, brandId, List.of("태그"));

		assertThat(repository.existsForBrand(brandId)).isTrue();
	}

	/**
```
new_string:
```java
	/**
```

- [ ] **Step 6: 컨트롤러 슬라이스 테스트의 스텁 정리**

`was/src/test/java/com/celfit/was/v1/brandmonitoring/V1BrandAccountsControllerTest.java`에서 아래를 고친다.

1. `given(hashtagTagRepository.existsForBrand(100L)).willReturn(true);` 스텁 줄이 **7곳**에 나온다 — 전부 삭제한다. 같은 줄이 반복되므로 Edit 도구 대신 아래 명령으로 지운다(macOS sed는 `-i ''` 필수).

```bash
sed -i '' '/given(hashtagTagRepository\.existsForBrand(100L))\.willReturn(true);/d' \
  was/src/test/java/com/celfit/was/v1/brandmonitoring/V1BrandAccountsControllerTest.java
grep -c "existsForBrand" was/src/test/java/com/celfit/was/v1/brandmonitoring/V1BrandAccountsControllerTest.java
```
Expected: 위 grep이 `1`(아래 2번에서 고칠 `should(never()).existsForBrand` 한 줄만 남음)

2. old_string:
```java
		then(hashtagTagRepository).should(never()).existsForBrand(anyLong());
	}
```
new_string:
```java
		then(commandClient).should(never()).getHashtagTags(anyString());
	}
```

3. old_string:
```java
		// 태그 조회(GET)는 이제 monitoring을 안 부르니 이 404 시나리오는 시딩(ensureSeeded)이 도는
		// 쓰기 경로(PUT)로만 재현된다 — existsForBrand=false라 ensureSeeded가 getHashtagTags를 부른다.
```
new_string:
```java
		// 태그 조회(GET)는 이제 monitoring을 안 부르니 이 404 시나리오는 승계(ensureSeeded)가 도는
		// 쓰기 경로(PUT)로만 재현된다 — ensureSeeded는 매번 getHashtagTags를 부른다(08-27 diff 개정).
```

- [ ] **Step 7: 통과 확인**

```bash
export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock
./gradlew :was:test --tests "com.celfit.was.v1.brandmonitoring.*" \
                    --tests "com.celfit.was.monitoring.BrandHashtagTagRepositoryTest"
```
Expected: PASS

- [ ] **Step 8: 모듈 전량 확인**

```bash
export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock
./gradlew :was:test
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 9: 커밋**

```bash
git add was/src/main/java/com/celfit/was/v1/brandmonitoring/V1BrandAccountService.java \
        was/src/main/java/com/celfit/was/monitoring/BrandHashtagTagRepository.java \
        was/src/test/java/com/celfit/was/v1/brandmonitoring/V1BrandAccountServiceHashtagTagsTest.java \
        was/src/test/java/com/celfit/was/v1/brandmonitoring/V1BrandAccountsControllerTest.java \
        was/src/test/java/com/celfit/was/monitoring/BrandHashtagTagRepositoryTest.java
git commit -m "fix(was): 태그 승계를 무주 태그 diff로 전환 - 백필 후 승계 미발동 회귀 차단"
```

---

## 완료 후

- [ ] **Step 1: 브랜치 push (PR은 사용자 승인 후에만 연다)**

```bash
git push -u origin HEAD
```

- [ ] **Step 2: 문서 아카이브(PR을 여는 커밋에 동봉)**

```bash
git mv docs/superpowers/plans/2026-08-27-hashtag-tag-ledger-fix.md \
       docs/superpowers/plans/archive/2026-08-27-hashtag-tag-ledger-fix.md
```
상태 헤더를 `> 상태: ✅ 구현됨 · 2026-08-27 작성`으로 바꾸고, `grep -rn "2026-08-27-hashtag-tag-ledger-fix" docs/`로 옛 경로 참조를 찾아 함께 고친다(특히 계획 2 문서의 전제 조건 링크).

- [ ] **Step 3: 배포 후 확인(운영 반영 시)**

백필이 실제로 채웠는지 app DB에서 확인한다 — 링크 수와 장부 보유 사용자 수가 일치해야 한다.

```sql
SELECT (SELECT count(*) FROM app.brand_monitorings WHERE deleted_at IS NULL)            AS 활성_링크,
       (SELECT count(DISTINCT (user_id, brand_id)) FROM app.brand_hashtag_tags)         AS 장부_보유_링크;
```
