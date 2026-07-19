# Swagger admin 게이트 구현 계획

> 상태: 🟢 활성 · 설계: [specs/2026-07-19-swagger-admin-gate-design.md](../specs/2026-07-19-swagger-admin-gate-design.md)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** prod에서 꺼져 있는 Swagger를 다시 켜되, `users.role=ADMIN` 계정만 HTTP Basic으로 열람하게 한다.

**Architecture:** Flyway V8로 `users.role`(USER/ADMIN) 도입 → `AppUserDetails`가 role 기반 `ROLE_*` 권한 발급(단, **transient** — 세션 직렬화 형상 불변) → 스웨거 경로 전용 `@Order(1)` SecurityFilterChain(HTTP Basic + STATELESS + `hasRole("ADMIN")`) → prod yml의 springdoc 비활성 제거. 기존 메인 체인(세션 쿠키 + /v1 401 JSON)은 스웨거 permitAll 한 줄 제거 외 무변경.

**Tech Stack:** Spring Boot 4.1 / Spring Security(세션 쿠키 + DaoAuthenticationProvider) / Flyway / springdoc 3 / Testcontainers(통합 테스트 베이스 `IntegrationTest`).

**핵심 제약(반드시 숙지):**
- `AppUserDetails`는 `app.spring_session_attributes`에 Java 직렬화된다. **비-transient 필드 추가 금지** — 형상이 바뀌면 기존 세션 역직렬화가 깨진다. role은 `transient`로 추가하고, 그래서 스웨거 체인은 세션 없이(STATELESS) 매 요청 Basic 재인증한다.
- 마이그레이션 번호는 **V8** (V7은 email_verifications가 사용 중).
- 주석·커밋 메시지는 한국어, 커밋 prefix `feat(was):`/`docs:`. 탭 들여쓰기(기존 파일 관용).
- 작업 위치: 워크트리 `/Users/woomin/Project/hypenow-backend/.worktrees/swagger-admin` (브랜치 `feat/swagger-admin-gate`).

---

### Task 1: users.role 컬럼 + AppUser 조회 배선

**Files:**
- Create: `was/src/main/resources/db/migration/app/V8__users_role.sql`
- Modify: `was/src/main/java/com/celfit/was/auth/AppUser.java`
- Modify: `was/src/main/java/com/celfit/was/auth/UserRepository.java` (insert / findByEmail / findById 3곳)
- Modify: `was/src/test/java/com/celfit/was/v1/account/V1MeControllerTest.java:88` (AppUser 생성자 시그니처)

- [ ] **Step 1: 마이그레이션 작성**

`was/src/main/resources/db/migration/app/V8__users_role.sql` 생성:

```sql
-- Swagger admin 게이트(설계 2026-07-19) — 최소 권한 체계. 기존 행은 전부 일반 사용자(USER)로 백필.
ALTER TABLE app.users
    ADD COLUMN role text NOT NULL DEFAULT 'USER'
        CHECK (role IN ('USER', 'ADMIN'));
```

- [ ] **Step 2: AppUser record에 role 추가**

`AppUser.java`의 record 선언을 다음으로 교체(주석은 유지):

```java
public record AppUser(long id, String email, String passwordHash, String role, OffsetDateTime createdAt) {
}
```

- [ ] **Step 3: UserRepository 3개 쿼리에 role 컬럼 추가**

`UserRepository.java` — DataClassRowMapper가 컬럼명→컴포넌트명 매핑이라 `role` 컬럼이 결과셋에 없으면 매핑이 깨진다. 3곳 모두 수정:

`insert()`의 RETURNING:
```java
				RETURNING id, email, password_hash, role, created_at
```

`findByEmail()`의 SELECT:
```java
				SELECT id, email, password_hash, role, created_at
```

`findById()`의 SELECT:
```java
				SELECT id, email, password_hash, role, created_at
```

- [ ] **Step 4: AppUser 생성자 호출부 컴파일 수정**

`V1MeControllerTest.java:88` — role 인자 `"USER"`를 passwordHash 뒤에 삽입:

```java
				new AppUser(7L, "user@example.com", PASSWORD_HASH, "USER", OffsetDateTime.parse("2026-06-01T00:00:00Z"))));
```

- [ ] **Step 5: 기존 테스트로 회귀 확인**

Run: `./gradlew :was:test --tests 'com.celfit.was.v1.account.V1MeControllerTest' --tests 'com.celfit.was.LoginWallIntegrationTest'`
Expected: PASS (마이그레이션 적용 + 매핑 무결 확인)

- [ ] **Step 6: Commit**

```bash
git add was/src/main/resources/db/migration/app/V8__users_role.sql \
        was/src/main/java/com/celfit/was/auth/AppUser.java \
        was/src/main/java/com/celfit/was/auth/UserRepository.java \
        was/src/test/java/com/celfit/was/v1/account/V1MeControllerTest.java
git commit -m "feat(was): users.role 컬럼 도입(V8) — USER/ADMIN 최소 권한 체계"
```

---

### Task 2: AppUserDetails 권한 발급 (transient role)

**Files:**
- Modify: `was/src/main/java/com/celfit/was/auth/AppUserDetails.java`
- Test(Create): `was/src/test/java/com/celfit/was/auth/AppUserDetailsTest.java`

- [ ] **Step 1: 실패하는 테스트 작성**

`was/src/test/java/com/celfit/was/auth/AppUserDetailsTest.java` 생성:

```java
package com.celfit.was.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;

/**
 * 권한 발급 + 직렬화 계약 — role은 transient라 세션 왕복 후에는 권한이 사라져야 한다
 * (세션 직렬화 형상 불변 유지 — 스웨거 Basic 체인만 인증 시점 권한을 쓴다).
 */
class AppUserDetailsTest {

	private static AppUserDetails details(String role) {
		return new AppUserDetails(
				new AppUser(1L, "a@b.c", "hash", role, OffsetDateTime.parse("2026-07-19T00:00:00Z")));
	}

	@Test
	void ADMIN이면_ROLE_ADMIN_권한을_발급한다() {
		assertThat(details("ADMIN").getAuthorities())
				.extracting(GrantedAuthority::getAuthority)
				.containsExactly("ROLE_ADMIN");
	}

	@Test
	void USER면_ROLE_USER_권한을_발급한다() {
		assertThat(details("USER").getAuthorities())
				.extracting(GrantedAuthority::getAuthority)
				.containsExactly("ROLE_USER");
	}

	@Test
	void 직렬화_왕복_후에는_권한이_비어_있다() throws Exception {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
			out.writeObject(details("ADMIN"));
		}
		AppUserDetails restored;
		try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
			restored = (AppUserDetails) in.readObject();
		}
		assertThat(restored.getAuthorities()).isEmpty();
		assertThat(restored.getUserId()).isEqualTo(1L); // 안정 필드는 보존
	}
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :was:test --tests 'com.celfit.was.auth.AppUserDetailsTest'`
Expected: FAIL — `getAuthorities()`가 빈 컬렉션이라 `containsExactly("ROLE_ADMIN")` 실패 (컴파일은 Task 1의 record 변경으로 통과)

- [ ] **Step 3: AppUserDetails 구현**

`AppUserDetails.java` 수정 — ① import에 `org.springframework.security.core.authority.SimpleGrantedAuthority` 추가, ② 클래스 javadoc의 마지막 문장(`권한 체계가 없어 authorities는 항상 비어 있다.`)을 다음으로 교체:

```java
 * role은 **transient** — 직렬화 형상 불변 조건을 지키면서 인증 시점 권한만 제공한다.
 * 세션에서 복원된 주체는 role=null이라 권한이 비어 있고, 스웨거 Basic 체인(STATELESS,
 * 매 요청 재인증)만 이 권한을 소비한다. 세션 기반 /v1 표면에서 role 검사를 하려면
 * 이 구조를 다시 설계할 것.
```

③ 필드·생성자·getAuthorities 수정:

```java
	private final long userId;
	private final String email;
	private final transient String role;
	private String password;

	public AppUserDetails(AppUser user) {
		this.userId = user.id();
		this.email = user.email();
		this.role = user.role();
		this.password = user.passwordHash();
	}
```

```java
	@Override
	public Collection<? extends GrantedAuthority> getAuthorities() {
		// 세션 복원 주체는 role=null(transient) — 그때는 무권한
		return role == null ? List.of() : List.of(new SimpleGrantedAuthority("ROLE_" + role));
	}
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew :was:test --tests 'com.celfit.was.auth.AppUserDetailsTest'`
Expected: PASS (3건)

- [ ] **Step 5: Commit**

```bash
git add was/src/main/java/com/celfit/was/auth/AppUserDetails.java \
        was/src/test/java/com/celfit/was/auth/AppUserDetailsTest.java
git commit -m "feat(was): AppUserDetails role 권한 발급 — transient로 세션 직렬화 형상 보존"
```

---

### Task 3: 스웨거 전용 SecurityFilterChain (admin 게이트)

**Files:**
- Modify: `was/src/main/java/com/celfit/was/config/SecurityConfig.java`
- Test(Modify): `was/src/test/java/com/celfit/was/OpenApiDocsIntegrationTest.java` (전면 재작성)

- [ ] **Step 1: 실패하는 통합 테스트 작성**

`OpenApiDocsIntegrationTest.java` 전체를 다음으로 교체:

```java
package com.celfit.was;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

/**
 * springdoc 스모크 + admin 게이트(설계 2026-07-19) — 스웨거 표면은 ADMIN만 열람한다.
 * 스키마 상세는 검증하지 않는다 — 정본은 프론트 API 스펙 문서, Swagger는 보조 문서.
 * 계정은 DB 직접 시드 — 가입 API 경유(V1AuthTestSteps)는 role 승격이 어차피 수동 SQL이라 우회 이득이 없다.
 */
@AutoConfigureMockMvc
class OpenApiDocsIntegrationTest extends IntegrationTest {

	private static final String PASSWORD = "Passw0rd!";
	private static final String ADMIN_EMAIL = "swagger-admin@test.io";
	private static final String USER_EMAIL = "swagger-user@test.io";

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcClient jdbcClient;

	@Autowired
	PasswordEncoder passwordEncoder;

	@BeforeEach
	void seedUsers() {
		insertUser(ADMIN_EMAIL, "ADMIN");
		insertUser(USER_EMAIL, "USER");
	}

	/** 컨테이너는 JVM 공유(IntegrationTest) — 재실행 대비 ON CONFLICT 멱등 시드. */
	private void insertUser(String email, String role) {
		jdbcClient.sql("""
				INSERT INTO app.users (email, password_hash, role)
				VALUES (:email, :hash, :role)
				ON CONFLICT (email) DO NOTHING""")
				.param("email", email)
				.param("hash", passwordEncoder.encode(PASSWORD))
				.param("role", role)
				.update();
	}

	@Test
	void 익명은_401과_Basic_팝업_헤더를_받는다() throws Exception {
		mockMvc.perform(get("/v3/api-docs"))
				.andExpect(status().isUnauthorized())
				.andExpect(header().exists("WWW-Authenticate"));
		mockMvc.perform(get("/swagger-ui/index.html"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void 일반_USER는_403이다() throws Exception {
		mockMvc.perform(get("/v3/api-docs").with(httpBasic(USER_EMAIL, PASSWORD)))
				.andExpect(status().isForbidden());
	}

	@Test
	void ADMIN은_v1_표면만_담긴_문서를_본다() throws Exception {
		mockMvc.perform(get("/v3/api-docs").with(httpBasic(ADMIN_EMAIL, PASSWORD)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.info.title").value("hypenow API"))
				.andExpect(jsonPath("$.paths['/v1/contents']").exists())
				// 구 /api 표면·내부 페이지는 paths-to-match(/v1/**) 밖 — 문서에 없어야 한다
				.andExpect(jsonPath("$.paths['/api/contents']").doesNotExist());
	}
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :was:test --tests 'com.celfit.was.OpenApiDocsIntegrationTest'`
Expected: FAIL — 익명·USER 케이스가 200으로 통과해버림(현재 permitAll). ADMIN 케이스는 PASS여도 무방.

- [ ] **Step 3: SecurityConfig에 스웨거 체인 추가**

`SecurityConfig.java` 수정 — ① import 추가:

```java
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.http.SessionCreationPolicy;
```

② 기존 `securityFilterChain` 빈 **위에** 스웨거 체인 빈 추가:

```java
	/**
	 * 스웨거 전용 체인(설계 2026-07-19) — ADMIN만 문서 열람. HTTP Basic 팝업으로 받고
	 * 기존 DaoAuthenticationProvider(users 테이블 + BCrypt)를 그대로 탄다.
	 * STATELESS: AppUserDetails.role이 transient라 세션 복원 주체엔 권한이 없다 —
	 * 세션을 만들지 않고 매 요청 재인증하는 게 정합. CSRF는 GET 전용 문서 표면이라 불필요.
	 */
	@Bean
	@Order(1)
	public SecurityFilterChain swaggerFilterChain(HttpSecurity http) throws Exception {
		http
				.securityMatcher("/swagger-ui/**", "/v3/api-docs/**")
				.authorizeHttpRequests(auth -> auth.anyRequest().hasRole("ADMIN"))
				.httpBasic(Customizer.withDefaults())
				.csrf(AbstractHttpConfigurer::disable)
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
		return http.build();
	}
```

③ 기존 `securityFilterChain` 빈에 `@Order(2)` 부여(`@Bean` 아래 줄):

```java
	@Bean
	@Order(2)
	public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
```

④ 메인 체인에서 스웨거 permitAll 한 줄 **삭제**:

```java
						.requestMatchers("/swagger-ui/**", "/v3/api-docs/**").permitAll() // 로컬·개발 문서(prod는 springdoc 비활성)
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew :was:test --tests 'com.celfit.was.OpenApiDocsIntegrationTest'`
Expected: PASS (3건)

- [ ] **Step 5: 메인 체인 회귀 확인**

Run: `./gradlew :was:test --tests 'com.celfit.was.LoginWallIntegrationTest' --tests 'com.celfit.was.SessionPersistenceIntegrationTest'`
Expected: PASS — 로그인 월·세션 동작 무변경

- [ ] **Step 6: Commit**

```bash
git add was/src/main/java/com/celfit/was/config/SecurityConfig.java \
        was/src/test/java/com/celfit/was/OpenApiDocsIntegrationTest.java
git commit -m "feat(was): 스웨거 admin 게이트 — 전용 Basic 체인(STATELESS)으로 ADMIN만 열람"
```

---

### Task 4: prod 노출 + 주석 정리

**Files:**
- Modify: `was/src/main/resources/application-prod.yml` (springdoc 비활성 블록 제거)
- Modify: `was/src/main/java/com/celfit/was/config/OpenApiConfig.java` (javadoc 갱신)

- [ ] **Step 1: prod yml에서 springdoc 비활성 제거**

`application-prod.yml`에서 다음 블록을 **삭제**하고:

```yaml
springdoc:
  # 운영에서는 Swagger 미노출(07-17 결정) — 문서는 로컬·개발에서만
  api-docs:
    enabled: false
  swagger-ui:
    enabled: false
```

같은 자리에 한 줄 주석으로 대체:

```yaml
# Swagger는 운영에도 노출(07-19 결정, 07-17 미노출 결정 대체) — 접근은 admin 게이트(SecurityConfig 스웨거 체인)가 통제
```

- [ ] **Step 2: OpenApiConfig javadoc 갱신**

`OpenApiConfig.java`의 javadoc 중 `prod에서는 노출하지 않는다(application-prod.yml에서 springdoc 비활성).` 문장을 다음으로 교체:

```java
 * 전 환경 노출하되 접근은 admin 게이트가 통제한다(SecurityConfig 스웨거 체인 — 07-19 결정).
```

- [ ] **Step 3: 전체 테스트**

Run: `./gradlew :was:test`
Expected: PASS (전건)

- [ ] **Step 4: Commit**

```bash
git add was/src/main/resources/application-prod.yml \
        was/src/main/java/com/celfit/was/config/OpenApiConfig.java
git commit -m "feat(was): 운영 Swagger 재노출 — 07-17 미노출 결정을 admin 게이트로 대체"
```

---

### Task 5: 문서 갱신 (ARCHITECTURE 결정 기록)

**Files:**
- Modify: `ARCHITECTURE.md` (§7 결정 기록 표 맨 위 + 머리의 `마지막 갱신` 날짜)

- [ ] **Step 1: 결정 기록 추가**

`ARCHITECTURE.md` §7 표의 **맨 위**(헤더 구분선 바로 아래)에 행 추가:

```markdown
| 2026-07-19 | **Swagger 운영 노출 + admin 게이트(07-17 미노출 결정 대체)** — users.role(USER/ADMIN, V8) 최소 권한 체계 도입, 스웨거 경로 전용 @Order(1) 체인(HTTP Basic 팝업·STATELESS·hasRole ADMIN). AppUserDetails.role은 transient — 세션 직렬화 형상 불변, 권한은 Basic 인증 시점에만 유효. 운영 반영엔 admin 계정 승격(수동 SQL `UPDATE app.users SET role='ADMIN'`) 필요 | [specs/2026-07-19-swagger-admin-gate-design.md](docs/superpowers/specs/2026-07-19-swagger-admin-gate-design.md) |
```

문서 상단 `> 마지막 갱신: 2026-07-18`을 `> 마지막 갱신: 2026-07-19`로 수정 (이미 07-19면 그대로 둔다).

- [ ] **Step 2: Commit**

```bash
git add ARCHITECTURE.md
git commit -m "docs: 결정 기록 — Swagger 운영 노출 + admin 게이트"
```

---

## 완료 후 (실행 세션 마무리)

- [ ] 스펙 상태 헤더를 `> 상태: 🟢 활성 · ✅ 구현됨`으로 갱신, 이 계획 파일을 `docs/superpowers/plans/archive/`로 이동 후 커밋 (`docs:` prefix)
- [ ] `develop` 대상 PR 생성 (superpowers:finishing-a-development-branch 스킬)
- [ ] PR 본문에 운영 반영 절차 명시: ① 배포 시 Flyway V8 자동 적용 ② admin 계정 준비(가입 코드 개통→가입→`UPDATE app.users SET role='ADMIN' WHERE email=...` 또는 bcrypt 해시 직접 INSERT) ③ `https://api.hypenow.io/swagger-ui/index.html` 접속 → Basic 팝업 → 문서 확인
