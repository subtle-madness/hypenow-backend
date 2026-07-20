# 가입 코드↔유저 관리자 조회 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 관리자가 `GET /admin/signups`로 "어떤 유저가 어떤 코드로 가입했는지"(소진+미소진 코드)를 브라우저에서 JSON으로 열람한다.

**Architecture:** 데이터는 이미 `app.signup_codes`(used_by/used_at)에 있으므로 조회 표면만 신설한다. 인증은 이미 머지된 ADMIN role + HTTP Basic 체인(`SecurityConfig`의 `@Order(1)`)을 재사용 — `securityMatcher`에 `/admin/**`만 추가한다. 조회는 `signup_codes LEFT JOIN users`.

**Tech Stack:** Java 21, Spring Boot 4.1, Spring Security(HTTP Basic + `hasRole`), JdbcClient(`query(RecordClass.class)` snake_case→camelCase 자동 매핑), Testcontainers Postgres 17, MockMvc.

---

## File Structure

- `was/src/main/java/com/celfit/was/admin/SignupUsageRow.java` — 응답 record (신설)
- `was/src/main/java/com/celfit/was/admin/AdminSignupRepository.java` — JdbcClient LEFT JOIN 조회 (신설)
- `was/src/main/java/com/celfit/was/admin/AdminSignupController.java` — `GET /admin/signups` (신설)
- `was/src/main/java/com/celfit/was/config/SecurityConfig.java` — `@Order(1)` 매처에 `/admin/**` 추가 (수정)
- `was/src/test/java/com/celfit/was/AdminSignupIntegrationTest.java` — 인증·정렬·null 케이스 (신설)

빌드 명령: `./gradlew :was:test`

---

## Task 1: 응답 record `SignupUsageRow`

**Files:**
- Create: `was/src/main/java/com/celfit/was/admin/SignupUsageRow.java`

- [ ] **Step 1: record 작성**

`used_by`는 nullable이라 `long`이 아닌 `Long`, `email`/`usedAt`도 nullable. 컬럼 별칭 `used_by AS user_id`가 `userId`로, `used_at`이 `usedAt`으로 매핑되도록 컴포넌트명을 맞춘다.

**`@JsonInclude`를 붙이지 않는다** — 이 코드베이스는 NON_NULL을 전역이 아니라 필드별 애노테이션으로만 적용하므로(예: `ContentCard`), 미부착 시 Jackson이 null을 **명시적으로 직렬화**(`"email": null`)한다. 미소진 코드 행이 `email/userId/usedAt: null`로 나오는 게 스펙 계약이라 이게 의도된 동작이다(키 부재가 아니라 명시적 null).

```java
package com.celfit.was.admin;

import java.time.OffsetDateTime;

/**
 * 관리자 가입 코드 사용 현황 한 행(설계 2026-07-19) — app.signup_codes LEFT JOIN app.users.
 * 미소진·탈퇴(FK ON DELETE SET NULL) 코드는 email·userId·usedAt이 null.
 * JdbcClient의 query(Class) 매핑 규약에 맞춰 SQL 별칭 user_id → userId, used_at → usedAt.
 */
public record SignupUsageRow(String code, String channel, String email, Long userId,
		OffsetDateTime usedAt) {
}
```

- [ ] **Step 2: 컴파일 확인**

Run: `./gradlew :was:compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 커밋**

```bash
git add was/src/main/java/com/celfit/was/admin/SignupUsageRow.java
git commit -m "feat(was): 관리자 가입 코드 사용 현황 응답 record"
```

---

## Task 2: 조회 리포지토리 `AdminSignupRepository`

**Files:**
- Create: `was/src/main/java/com/celfit/was/admin/AdminSignupRepository.java`

- [ ] **Step 1: 리포지토리 작성**

`ContentListRepository`의 `.query(RecordClass.class).list()` 관용구를 따른다. 소진 코드가 위(최근순), 미소진 코드는 `NULLS LAST`로 아래.

```java
package com.celfit.was.admin;

import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 관리자 가입 코드 사용 현황 조회(설계 2026-07-19) — app 스키마만 읽는다(was 경계).
 * signup_codes 전체를 users와 LEFT JOIN해 소진(누가 썼는지)·미소진 코드를 한 번에 반환한다.
 */
@Repository
public class AdminSignupRepository {

	private final JdbcClient jdbcClient;

	public AdminSignupRepository(JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	public List<SignupUsageRow> findAll() {
		return jdbcClient.sql("""
				SELECT sc.code, sc.channel, u.email, sc.used_by AS user_id, sc.used_at
				FROM app.signup_codes sc
				LEFT JOIN app.users u ON u.id = sc.used_by
				ORDER BY sc.used_at DESC NULLS LAST, sc.code""")
				.query(SignupUsageRow.class)
				.list();
	}
}
```

- [ ] **Step 2: 컴파일 확인**

Run: `./gradlew :was:compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 커밋**

```bash
git add was/src/main/java/com/celfit/was/admin/AdminSignupRepository.java
git commit -m "feat(was): 가입 코드↔유저 LEFT JOIN 조회 리포지토리"
```

---

## Task 3: 컨트롤러 `AdminSignupController`

**Files:**
- Create: `was/src/main/java/com/celfit/was/admin/AdminSignupController.java`

- [ ] **Step 1: 컨트롤러 작성**

raw JSON 배열 반환(/v1 envelope 아님 — 관리자 내부 표면). `@RestController` + `@GetMapping`.

```java
package com.celfit.was.admin;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 가입 코드 조회 API(설계 2026-07-19) — GET /admin/signups.
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
}
```

- [ ] **Step 2: 컴파일 확인**

Run: `./gradlew :was:compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 커밋**

```bash
git add was/src/main/java/com/celfit/was/admin/AdminSignupController.java
git commit -m "feat(was): GET /admin/signups 컨트롤러"
```

---

## Task 4: 인증 게이트 — `/admin/**`를 ADMIN Basic 체인에 편입

**Files:**
- Modify: `was/src/main/java/com/celfit/was/config/SecurityConfig.java`

- [ ] **Step 1: `@Order(1)` 체인 매처에 `/admin/**` 추가 + bean명·주석 일반화**

현재(스웨거 전용):

```java
	/**
	 * 스웨거 전용 체인(설계 2026-07-19) — ADMIN만 문서 열람. HTTP Basic 팝업으로 받고
	 * 기존 DaoAuthenticationProvider(users 테이블 + BCrypt)를 그대로 탄다.
	 * STATELESS는 보안 불변식: 세션엔 로그인 시점 권한 스냅샷(토큰 authorities)이 남아
	 * 세션을 읽으면 강등된 admin이 로그아웃 전까지 통과한다 — 매 요청 Basic 재인증으로
	 * 현재 DB role을 읽는다. CSRF는 GET 전용 문서 표면이라 불필요.
	 * 매처는 springdoc 기본 경로와 결합돼 있다(/swagger-ui.html 진입점·/v3/api-docs.yaml 포함) —
	 * springdoc.api-docs.path·swagger-ui.path를 바꾸면 여기도 같이 바꿀 것.
	 */
	@Bean
	@Order(1)
	public SecurityFilterChain swaggerFilterChain(HttpSecurity http) throws Exception {
		http
				.securityMatcher("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**", "/v3/api-docs.yaml")
				.authorizeHttpRequests(auth -> auth.anyRequest().hasRole("ADMIN"))
				.httpBasic(Customizer.withDefaults())
				.csrf(AbstractHttpConfigurer::disable)
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
		return http.build();
	}
```

변경 후(스웨거 + admin API 공통 Basic 체인):

```java
	/**
	 * ADMIN Basic 체인(설계 2026-07-19) — 스웨거 문서와 /admin/** 관리자 API를 ADMIN만 열람.
	 * HTTP Basic 팝업으로 받고 기존 DaoAuthenticationProvider(users 테이블 + BCrypt)를 그대로 탄다.
	 * STATELESS는 보안 불변식: 세션엔 로그인 시점 권한 스냅샷(토큰 authorities)이 남아
	 * 세션을 읽으면 강등된 admin이 로그아웃 전까지 통과한다 — 매 요청 Basic 재인증으로
	 * 현재 DB role을 읽는다. CSRF는 GET 전용 표면(문서·조회)이라 불필요.
	 * 스웨거 매처는 springdoc 기본 경로와 결합돼 있다(/swagger-ui.html 진입점·/v3/api-docs.yaml 포함) —
	 * springdoc.api-docs.path·swagger-ui.path를 바꾸면 여기도 같이 바꿀 것.
	 */
	@Bean
	@Order(1)
	public SecurityFilterChain adminBasicFilterChain(HttpSecurity http) throws Exception {
		http
				.securityMatcher("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**", "/v3/api-docs.yaml",
						"/admin/**")
				.authorizeHttpRequests(auth -> auth.anyRequest().hasRole("ADMIN"))
				.httpBasic(Customizer.withDefaults())
				.csrf(AbstractHttpConfigurer::disable)
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
		return http.build();
	}
```

- [ ] **Step 2: 컴파일 확인**

Run: `./gradlew :was:compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 커밋**

```bash
git add was/src/main/java/com/celfit/was/config/SecurityConfig.java
git commit -m "feat(was): /admin/** 를 ADMIN Basic 체인에 편입"
```

---

## Task 5: 통합 테스트 — 인증·정렬·null 케이스

**Files:**
- Create: `was/src/test/java/com/celfit/was/AdminSignupIntegrationTest.java`

`IntegrationTest`(Testcontainers) 베이스를 상속하고 `@AutoConfigureMockMvc`. 실 DB에 유저·코드를 시드하고 실제 Basic 인증 체인을 탄다. `PasswordEncoder` 빈으로 BCrypt 해시를 만들어 ADMIN/일반 유저를 직접 INSERT한다(app.users는 email·password_hash 외 컬럼이 V9로 nullable, role은 V11 기본 USER).

- [ ] **Step 1: 실패하는 테스트 작성**

`IntegrationTest`는 싱글턴 Postgres를 JVM 전체에서 공유하고 롤백하지 않는다 — 다른 테스트가 시드한 코드 행이 남아 있으므로 **위치(`$[0]`) 기반 단언은 쓰지 않는다.** 필드 정확성은 코드 값으로 필터하는 jsonPath로, 정렬(소진이 미소진보다 앞: NULLS LAST)은 리포지토리 반환 리스트에서 두 코드의 인덱스를 비교해 검증한다(전역에서 used < unused는 항상 성립).

```java
package com.celfit.was;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import com.celfit.was.admin.AdminSignupRepository;
import com.celfit.was.admin.SignupUsageRow;

/**
 * 관리자 가입 코드 조회(설계 2026-07-19) — /admin/**는 ADMIN Basic 체인(SecurityConfig @Order(1)).
 * 실 DB에 유저·코드를 시드해 인증 경계와 소진/미소진 정렬을 검증한다.
 * 싱글턴 DB 공유·무롤백이라 전역 위치 대신 코드 값 필터·상대 인덱스로 단언한다.
 */
@AutoConfigureMockMvc
class AdminSignupIntegrationTest extends IntegrationTest {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcClient jdbcClient;

	@Autowired
	PasswordEncoder passwordEncoder;

	@Autowired
	AdminSignupRepository repository;

	private long seedUser(String email, String role) {
		return jdbcClient.sql("""
				INSERT INTO app.users (email, password_hash, role) VALUES (:email, :hash, :role)
				RETURNING id""")
				.param("email", email)
				.param("hash", passwordEncoder.encode("Passw0rd!"))
				.param("role", role)
				.query(Long.class)
				.single();
	}

	private void seedUsedCode(String code, String channel, long userId) {
		jdbcClient.sql("""
				INSERT INTO app.signup_codes (code, channel, used_by, used_at)
				VALUES (:code, :channel, :userId, now())""")
				.param("code", code)
				.param("channel", channel)
				.param("userId", userId)
				.update();
	}

	private void seedUnusedCode(String code, String channel) {
		jdbcClient.sql("INSERT INTO app.signup_codes (code, channel) VALUES (:code, :channel)")
				.param("code", code)
				.param("channel", channel)
				.update();
	}

	private int indexOfCode(List<SignupUsageRow> rows, String code) {
		for (int i = 0; i < rows.size(); i++) {
			if (rows.get(i).code().equals(code)) {
				return i;
			}
		}
		return -1;
	}

	@Test
	void 미인증이면_401() throws Exception {
		mockMvc.perform(get("/admin/signups"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void ADMIN_아니면_403() throws Exception {
		seedUser("user-403@x.com", "USER");
		mockMvc.perform(get("/admin/signups").with(httpBasic("user-403@x.com", "Passw0rd!")))
				.andExpect(status().isForbidden());
	}

	@Test
	void ADMIN이면_소진코드는_유저와_미소진코드는_null로_반환() throws Exception {
		long memberId = seedUser("member@x.com", "USER");
		seedUser("admin@x.com", "ADMIN");
		seedUsedCode("THREADS-USED", "THREADS", memberId);
		seedUnusedCode("DM-OPEN", "DM");

		// HTTP 계약: 200 + 소진 코드의 email 채워짐, 미소진 코드의 email 키가 명시적 null로 존재.
		// 필터([?()])는 indefinite path라 결과가 리스트 → contains 매처로 단언.
		mockMvc.perform(get("/admin/signups").with(httpBasic("admin@x.com", "Passw0rd!")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.code=='THREADS-USED')].email")
						.value(org.hamcrest.Matchers.contains("member@x.com")))
				.andExpect(jsonPath("$[?(@.code=='DM-OPEN')].email")
						.value(org.hamcrest.Matchers.contains(org.hamcrest.Matchers.nullValue())));

		// 필드·정렬 정밀 단언은 리포지토리 반환에서(위치 무관, null 명확).
		// 소진(THREADS-USED)이 미소진(DM-OPEN)보다 앞 — NULLS LAST는 전역에서 항상 성립.
		List<SignupUsageRow> rows = repository.findAll();
		SignupUsageRow used = rows.stream().filter(r -> r.code().equals("THREADS-USED")).findFirst().orElseThrow();
		assertThat(used.email()).isEqualTo("member@x.com");
		assertThat(used.channel()).isEqualTo("THREADS");
		assertThat(used.userId()).isEqualTo(memberId);
		assertThat(used.usedAt()).isNotNull();
		SignupUsageRow open = rows.stream().filter(r -> r.code().equals("DM-OPEN")).findFirst().orElseThrow();
		assertThat(open.email()).isNull();
		assertThat(open.userId()).isNull();
		assertThat(open.usedAt()).isNull();
		assertThat(indexOfCode(rows, "THREADS-USED")).isLessThan(indexOfCode(rows, "DM-OPEN"));
	}
}
```

- [ ] **Step 2: 실패 확인 (컨트롤러/매처 없으면 실패, 있으면 통과)**

Run: `./gradlew :was:test --tests com.celfit.was.AdminSignupIntegrationTest`
Expected: Task 1~4 이후이므로 PASS. (TDD 순서를 엄격히 하려면 이 테스트를 Task 1 이전에 작성해 컴파일 실패 → 순차 구현으로 통과시켜도 된다.)

- [ ] **Step 3: 커밋**

```bash
git add was/src/test/java/com/celfit/was/AdminSignupIntegrationTest.java
git commit -m "test(was): 관리자 가입 코드 조회 인증·정렬·null 통합 테스트"
```

---

## Task 6: 전체 테스트 + 문서 상태 갱신

**Files:**
- Modify: `docs/superpowers/specs/2026-07-19-admin-signup-codes-design.md` (상태 헤더 ✅)
- Modify: `ARCHITECTURE.md` (§5 작업 트랙 / §7 결정 기록 — 해당 시)

- [ ] **Step 1: was 전체 테스트**

Run: `./gradlew :was:test`
Expected: BUILD SUCCESSFUL (기존 테스트 무회귀)

- [ ] **Step 2: 스펙 상태 헤더를 ✅ 구현됨으로 갱신**

`> 상태: 🟢 활성 · 설계 확정(2026-07-19)` → `> 상태: ✅ 구현됨(2026-07-19) · 설계 확정(2026-07-19)`

- [ ] **Step 3: ARCHITECTURE.md §5/§7 갱신**

§7 결정 기록에 한 줄 추가: "관리자 가입 코드 조회(GET /admin/signups) — 기존 ADMIN Basic 체인 재사용, app.signup_codes LEFT JOIN users." §5 작업 트랙 표에 항목이 있으면 상태 반영.

- [ ] **Step 4: 커밋**

```bash
git add docs/superpowers/specs/2026-07-19-admin-signup-codes-design.md ARCHITECTURE.md
git commit -m "docs: 관리자 가입 코드 조회 구현 반영(상태·결정 기록)"
```

---

## 운영 반영(배포 시, 코드 머지 후)

계획 실행 범위 밖이지만 열람하려면 필요:

1. 관리자 계정 승격: `UPDATE app.users SET role='ADMIN' WHERE email='<관리자 이메일>';`
   (운영 users가 0건이면 가입 코드로 먼저 가입 후 승격.)
2. 열람: `https://api.hypenow.io/admin/signups` → 브라우저 Basic 팝업에 ADMIN 유저 이메일/비번 입력.
