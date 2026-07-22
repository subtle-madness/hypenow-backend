# 가입 코드 is_sent 칼럼 + 변경 API 구현 계획

> 상태: 🟢 활성
>
> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `app.signup_codes`에 발송 여부(`is_sent`) 칼럼을 추가하고, 어드민 FE가 체크/해제하는 `PATCH /admin/signup-codes/{code}` API와 조회 반영·Swagger 노출을 구현한다.

**Architecture:** 스펙 [2026-07-22-signup-codes-is-sent-design.md](../specs/2026-07-22-signup-codes-is-sent-design.md). Flyway 마이그레이션(V12) → `AdminSignupRepository`에 UPDATE 추가 → 사람용 `AdminSignupController`에 PATCH 추가(ADMIN Basic 체인 자동 적용 — `@Order(0)` 토큰 체인 매처는 정확히 `/admin/signup-codes`라 하위 경로는 안 잡음, SecurityConfig 무수정). springdoc `paths-to-match`에 `/admin/**` 추가.

**Tech Stack:** Java 21, Spring Boot 4.1, JdbcClient, Flyway, Testcontainers(MockMvc 통합 테스트), springdoc.

## Global Constraints

- 주석·커밋 메시지는 한국어, 커밋 prefix `feat(was):`/`docs:` (CLAUDE.md).
- was는 `app` 스키마에만 쓴다(시스템 경계). DTO는 record. 조회는 JdbcClient.
- 전체 테스트: `./gradlew :was:test` (Docker 필요 — Testcontainers).
- 테스트 클래스는 싱글턴 DB 공유·무롤백 — 코드·이메일은 UUID로 유니크 발급, 전역 위치 단언 금지(AdminSignupIntegrationTest 상단 주석 참고).

---

### Task 1: V12 마이그레이션 + 조회(isSent) 반영

**Files:**
- Create: `was/src/main/resources/db/migration/app/V12__signup_codes_is_sent.sql`
- Modify: `was/src/main/java/com/celfit/was/admin/SignupUsageRow.java`
- Modify: `was/src/main/java/com/celfit/was/admin/AdminSignupRepository.java` (findAll SELECT)
- Test: `was/src/test/java/com/celfit/was/AdminSignupIntegrationTest.java`

**Interfaces:**
- Produces: `SignupUsageRow(String code, String channel, String email, Long userId, OffsetDateTime usedAt, boolean isSent)` — Task 2 테스트가 `isSent()`를 읽는다.

- [ ] **Step 1: 실패하는 테스트 작성**

`AdminSignupIntegrationTest.java`에 테스트 추가:

```java
	@Test
	void 새로_적재된_코드는_isSent가_false다() throws Exception {
		String adminEmail = uniqueEmail("admin-issent");
		seedUser(adminEmail, "ADMIN");
		String code = uniqueCode("DM-ISSENT");
		seedUnusedCode(code, "DM");

		// HTTP 계약: 조회 응답에 isSent 키가 false로 존재.
		mockMvc.perform(get("/admin/signups").with(httpBasic(adminEmail, "Passw0rd!")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.code=='" + code + "')].isSent")
						.value(org.hamcrest.Matchers.contains(false)));

		SignupUsageRow row = repository.findAll().stream()
				.filter(r -> r.code().equals(code)).findFirst().orElseThrow();
		assertThat(row.isSent()).isFalse();
	}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :was:compileTestJava`
Expected: FAIL — `SignupUsageRow`에 `isSent()` 없음(컴파일 에러).

- [ ] **Step 3: 마이그레이션 + record 필드 + SELECT 구현**

`V12__signup_codes_is_sent.sql` 생성:

```sql
-- 발송 여부(설계 2026-07-22) — 어드민이 코드를 대상자에게 전달했는지 표시.
-- 소진(used_at)과 별개 축: 보냈지만 미가입, 안 보냈는데 소진(직접 전달) 모두 가능.
ALTER TABLE app.signup_codes ADD COLUMN is_sent boolean NOT NULL DEFAULT false;
```

`SignupUsageRow.java` — 필드 추가(javadoc에 한 줄 덧붙임):

```java
/**
 * 관리자 가입 코드 사용 현황 한 행(설계 2026-07-19) — app.signup_codes LEFT JOIN app.users.
 * 세 가지 상태가 있다: ① 미소진 — email·userId·usedAt 모두 null. ② 소진+탈퇴(used_by가
 * ON DELETE SET NULL로 끊긴 경우) — email·userId는 null이지만 usedAt은 소진 시각 그대로 유지된다
 * (소진 판정의 정본은 used_at이지 used_by가 아니다 — V8 `signup_codes` 주석 참고). ③ 소진+생존 —
 * 셋 다 채워진다.
 * isSent는 발송 여부(설계 2026-07-22) — 소진과 별개 축, PATCH /admin/signup-codes/{code}로 변경.
 * JdbcClient의 query(Class) 매핑 규약에 맞춰 SQL 별칭 user_id → userId, used_at → usedAt.
 */
public record SignupUsageRow(String code, String channel, String email, Long userId,
		OffsetDateTime usedAt, boolean isSent) {
}
```

`AdminSignupRepository.findAll()` SELECT에 `sc.is_sent` 추가:

```java
	public List<SignupUsageRow> findAll() {
		return jdbcClient.sql("""
				SELECT sc.code, sc.channel, u.email, sc.used_by AS user_id, sc.used_at, sc.is_sent
				FROM app.signup_codes sc
				LEFT JOIN app.users u ON u.id = sc.used_by
				ORDER BY sc.used_at DESC NULLS LAST, sc.code""")
				.query(SignupUsageRow.class)
				.list();
	}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :was:test --tests AdminSignupIntegrationTest`
Expected: PASS (기존 테스트 포함 전부).

- [ ] **Step 5: 커밋**

```bash
git add was/src/main/resources/db/migration/app/V12__signup_codes_is_sent.sql \
  was/src/main/java/com/celfit/was/admin/SignupUsageRow.java \
  was/src/main/java/com/celfit/was/admin/AdminSignupRepository.java \
  was/src/test/java/com/celfit/was/AdminSignupIntegrationTest.java
git commit -m "feat(was): signup_codes is_sent 칼럼 추가·어드민 조회 반영"
```

---

### Task 2: PATCH /admin/signup-codes/{code} 발송 표시 API

**Files:**
- Create: `was/src/main/java/com/celfit/was/admin/SignupCodeSentRequest.java`
- Create: `was/src/main/java/com/celfit/was/admin/SignupCodeSentResponse.java`
- Modify: `was/src/main/java/com/celfit/was/admin/AdminSignupRepository.java` (updateIsSent 추가)
- Modify: `was/src/main/java/com/celfit/was/admin/AdminSignupController.java` (PATCH 추가)
- Modify: `was/src/main/java/com/celfit/was/admin/AdminApiExceptionAdvice.java` (assignableTypes 확장)
- Test: `was/src/test/java/com/celfit/was/AdminSignupIntegrationTest.java`

**Interfaces:**
- Consumes: Task 1의 `SignupUsageRow.isSent()`.
- Produces: `PATCH /admin/signup-codes/{code}` — 바디 `{"isSent": bool}`, 200 `{"code":..., "isSent":...}` / 400 / 404. `AdminSignupRepository.updateIsSent(String code, boolean isSent)` → 갱신 행 수(int).

- [ ] **Step 1: 실패하는 테스트 작성**

`AdminSignupIntegrationTest.java`에 import 추가:

```java
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import org.springframework.http.MediaType;
```

테스트 4개 추가:

```java
	@Test
	void PATCH로_발송_표시를_켜고_끌_수_있다() throws Exception {
		String adminEmail = uniqueEmail("admin-sent");
		seedUser(adminEmail, "ADMIN");
		String code = uniqueCode("DM-SENT");
		seedUnusedCode(code, "DM");

		mockMvc.perform(patch("/admin/signup-codes/" + code)
				.with(httpBasic(adminEmail, "Passw0rd!"))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"isSent\": true}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.code").value(code))
				.andExpect(jsonPath("$.isSent").value(true));
		assertThat(repository.findAll().stream()
				.filter(r -> r.code().equals(code)).findFirst().orElseThrow().isSent()).isTrue();

		// 양방향 — 다시 끄기(실수 복구).
		mockMvc.perform(patch("/admin/signup-codes/" + code)
				.with(httpBasic(adminEmail, "Passw0rd!"))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"isSent\": false}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.isSent").value(false));
		assertThat(repository.findAll().stream()
				.filter(r -> r.code().equals(code)).findFirst().orElseThrow().isSent()).isFalse();
	}

	@Test
	void 없는_코드_PATCH는_404() throws Exception {
		String adminEmail = uniqueEmail("admin-404");
		seedUser(adminEmail, "ADMIN");

		mockMvc.perform(patch("/admin/signup-codes/" + uniqueCode("NOPE"))
				.with(httpBasic(adminEmail, "Passw0rd!"))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"isSent\": true}"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error").exists());
	}

	@Test
	void isSent_누락_PATCH는_400() throws Exception {
		String adminEmail = uniqueEmail("admin-400");
		seedUser(adminEmail, "ADMIN");
		String code = uniqueCode("DM-400");
		seedUnusedCode(code, "DM");

		mockMvc.perform(patch("/admin/signup-codes/" + code)
				.with(httpBasic(adminEmail, "Passw0rd!"))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").exists());
	}

	@Test
	void 미인증_PATCH는_401() throws Exception {
		// @Order(0) 토큰 체인 매처는 정확히 /admin/signup-codes — 하위 경로는 Basic 체인(401 챌린지)에 떨어진다.
		mockMvc.perform(patch("/admin/signup-codes/" + uniqueCode("DM-ANON"))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"isSent\": true}"))
				.andExpect(status().isUnauthorized());
	}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :was:test --tests AdminSignupIntegrationTest`
Expected: FAIL — PATCH 매핑이 없어 4개 테스트 실패(404/401 등 기대 불일치).

- [ ] **Step 3: 구현**

`SignupCodeSentRequest.java` 생성:

```java
package com.celfit.was.admin;

/** 발송 표시 변경 요청(설계 2026-07-22) — Boolean 래퍼로 받아 누락(null)을 400으로 구분한다. */
public record SignupCodeSentRequest(Boolean isSent) {
}
```

`SignupCodeSentResponse.java` 생성:

```java
package com.celfit.was.admin;

/** 발송 표시 변경 결과(설계 2026-07-22) — 반영된 최종 상태를 그대로 돌려준다(멱등 PATCH). */
public record SignupCodeSentResponse(String code, boolean isSent) {
}
```

`AdminSignupRepository.java`에 메서드 추가:

```java
	/** 발송 표시 갱신 — 반환 0이면 코드 없음(호출부가 404 판정). */
	public int updateIsSent(String code, boolean isSent) {
		return jdbcClient.sql("UPDATE app.signup_codes SET is_sent = :isSent WHERE code = :code")
				.param("isSent", isSent)
				.param("code", code)
				.update();
	}
```

`AdminSignupController.java` — import 추가(`PatchMapping`, `PathVariable`, `RequestBody`) 후 메서드 추가, javadoc에 PATCH 한 줄 덧붙임:

```java
package com.celfit.was.admin;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 가입 코드 조회 API(설계 2026-07-19) — GET /admin/signups.
 * 발송 표시 변경(설계 2026-07-22) — PATCH /admin/signup-codes/{code}. @Order(0) 토큰 체인 매처는
 * 정확히 /admin/signup-codes라 하위 경로는 안 잡는다 — 이 PATCH는 Basic 체인 소속.
 * 인증은 SecurityConfig의 @Order(1) ADMIN Basic 체인이 담당(/admin/** 매처). 여기선 role 검사를 하지 않는다.
 */
@RestController
public class AdminSignupController {

	private final AdminSignupRepository repository;

	public AdminSignupController(AdminSignupRepository repository) {
		this.repository = repository;
	}

	@GetMapping("/admin/signups")
	public List<SignupUsageRow> signups() {
		return repository.findAll();
	}

	@PatchMapping("/admin/signup-codes/{code}")
	public SignupCodeSentResponse updateSent(@PathVariable String code,
			@RequestBody SignupCodeSentRequest request) {
		if (request.isSent() == null) {
			throw new AdminApiException(400, "isSent가 필요합니다.");
		}
		if (repository.updateIsSent(code, request.isSent()) == 0) {
			throw new AdminApiException(404, "존재하지 않는 코드입니다: " + code);
		}
		return new SignupCodeSentResponse(code, request.isSent());
	}
}
```

`AdminApiExceptionAdvice.java` — assignableTypes 확장(주석도 갱신):

```java
/**
 * 어드민 쓰기 API 에러 렌더(설계 2026-07-20, 07-22 확장) — 어드민이 본문을 그대로 노출하므로 {"error":메시지}.
 * assignableTypes로 어드민 쓰기 컨트롤러(적재·발송 표시)에만 적용.
 */
@RestControllerAdvice(assignableTypes = {AdminSignupCodeController.class, AdminSignupController.class})
public class AdminApiExceptionAdvice {
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :was:test --tests AdminSignupIntegrationTest`
Expected: PASS (전체).

- [ ] **Step 5: 커밋**

```bash
git add was/src/main/java/com/celfit/was/admin/ \
  was/src/test/java/com/celfit/was/AdminSignupIntegrationTest.java
git commit -m "feat(was): 가입 코드 발송 표시 PATCH API"
```

---

### Task 3: Swagger에 어드민 API 표면 포함

**Files:**
- Modify: `was/src/main/resources/application.yml` (springdoc.paths-to-match)
- Test: `was/src/test/java/com/celfit/was/OpenApiDocsIntegrationTest.java`

**Interfaces:**
- Consumes: Task 2의 `PATCH /admin/signup-codes/{code}` 매핑(문서에 나타나는지 단언).
- Produces: 없음(설정 변경).

- [ ] **Step 1: 실패하는 테스트 작성**

`OpenApiDocsIntegrationTest.java`에 테스트 추가(기존 상수 `ADMIN_EMAIL`·`PASSWORD` 재사용):

```java
	@Test
	void 어드민_API도_문서화된다() throws Exception {
		// 07-22 결정: paths-to-match에 /admin/** 추가 — 접근은 기존 ADMIN 게이트가 통제하므로 보안 변화 없음.
		mockMvc.perform(get("/v3/api-docs").with(httpBasic(ADMIN_EMAIL, PASSWORD)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.paths['/admin/signups']").exists())
				.andExpect(jsonPath("$.paths['/admin/signup-codes/{code}']").exists());
	}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :was:test --tests OpenApiDocsIntegrationTest`
Expected: FAIL — `/admin/**`가 paths-to-match 밖이라 `$.paths['/admin/signups']` 부재.

- [ ] **Step 3: 설정 변경**

`application.yml`의 springdoc 블록 수정:

```yaml
springdoc:
  paths-to-match: /v1/**, /admin/**    # /v1 표면 + 어드민 API(07-22) — 구 /api·내부 페이지(postdemo 등)는 제외
```

(기존 `swagger-ui.csrf` 설정은 그대로 유지.)

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :was:test --tests OpenApiDocsIntegrationTest`
Expected: PASS (기존 스모크 포함 전부).

- [ ] **Step 5: 커밋**

```bash
git add was/src/main/resources/application.yml \
  was/src/test/java/com/celfit/was/OpenApiDocsIntegrationTest.java
git commit -m "feat(was): Swagger에 어드민 API 표면 포함"
```

---

### Task 4: 전체 검증 + 문서 갱신

**Files:**
- Modify: `docs/superpowers/specs/2026-07-22-signup-codes-is-sent-design.md` (상태 헤더)
- Modify: `ARCHITECTURE.md` (§7 결정 기록)

**Interfaces:**
- Consumes: Task 1–3 전부 완료 상태.
- Produces: 없음(문서).

- [ ] **Step 1: was 전체 테스트**

Run: `./gradlew :was:test`
Expected: BUILD SUCCESSFUL — 실패 0.

- [ ] **Step 2: 문서 갱신**

- 스펙 상태 헤더를 `> 상태: ✅ 구현됨(2026-07-22)`으로 변경.
- ARCHITECTURE.md §7(결정 기록)에 한 줄 추가(표 형식은 기존 행을 따른다):
  Swagger 표면을 `/v1/**`에서 `/v1/**, /admin/**`로 확장 — 어드민 FE가 어드민 API 시그니처를
  Swagger에서 확인(접근은 기존 ADMIN Basic 게이트). signup_codes에 is_sent(발송 여부, 소진과 별개 축) 추가.

- [ ] **Step 3: 커밋**

```bash
git add docs/superpowers/specs/2026-07-22-signup-codes-is-sent-design.md ARCHITECTURE.md
git commit -m "docs: is_sent 구현 반영 — 스펙 상태·결정 기록 갱신"
```

(계획 문서의 `plans/archive/` 이동은 브랜치 머지 시점에 수행.)
