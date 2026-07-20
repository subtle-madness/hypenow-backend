# 가입 코드 일괄 적재 API Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans. Steps use checkbox (`- [ ]`) syntax.

**Goal:** `POST /admin/signup-codes` (정적 토큰 인증)로 코드 배열을 `app.signup_codes`에 일괄 저장, `{inserted, skipped}` 반환.

**Architecture:** 사람용 `/admin/**` ADMIN Basic 체인(@Order(1))보다 우선하는 `@Order(0)` 토큰 체인을 신설해 이 경로만 `Bearer <CODES_API_KEY>`로 잠근다. channel은 코드 접두사에서 유도(접두사 없으면 400), 중복은 `ON CONFLICT DO NOTHING`으로 스킵.

**Tech Stack:** Java 21, Spring Boot 4.1/Spring Security(커스텀 OncePerRequestFilter), JdbcClient, Testcontainers.

---

## File Structure (all under `was/src/main/java/com/celfit/was/admin/` unless noted)

- `SignupCodeCreateRequest.java` / `SignupCodeCreateResponse.java` — 요청·응답 record
- `AdminSignupCodeRepository.java` — ON CONFLICT 단건 INSERT
- `AdminSignupCodeService.java` — 검증·channel 유도·삽입·집계
- `AdminSignupCodeController.java` — `POST /admin/signup-codes`
- `AdminApiException.java` + `AdminApiExceptionAdvice.java` — `{"error":...}` 렌더링
- `CodesApiKeyAuthFilter.java` — Bearer 토큰 상수시간 검증·fail-closed
- `config/SecurityConfig.java` — `@Order(0)` 토큰 체인 추가(수정)
- `resources/application.yml` — `codes.api-key: ${CODES_API_KEY:}` (수정)
- `src/test/java/com/celfit/was/AdminSignupCodeIngestIntegrationTest.java` + `...FailClosedTest.java` — 테스트

빌드: `./gradlew :was:test`

---

## Task 1: 요청·응답 record

**Files:** Create `admin/SignupCodeCreateRequest.java`, `admin/SignupCodeCreateResponse.java`

- [ ] **Step 1: record 작성**

```java
package com.celfit.was.admin;

import java.util.List;

/** 가입 코드 일괄 적재 요청(설계 2026-07-20) — codes는 PREFIX-XXXX 형식, 배치 ≤500. */
public record SignupCodeCreateRequest(List<String> codes) {
}
```

```java
package com.celfit.was.admin;

/** 적재 결과 — inserted=신규 저장 수, skipped=중복 등으로 건너뛴 수(제출 수 − inserted). */
public record SignupCodeCreateResponse(int inserted, int skipped) {
}
```

- [ ] **Step 2:** `./gradlew :was:compileJava` → BUILD SUCCESSFUL
- [ ] **Step 3:** commit `feat(was): 가입 코드 적재 요청·응답 record`

---

## Task 2: 리포지토리

**Files:** Create `admin/AdminSignupCodeRepository.java`

- [ ] **Step 1: 작성**

```java
package com.celfit.was.admin;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 가입 코드 단건 삽입(설계 2026-07-20) — app 스키마만 씀(was 경계).
 * ON CONFLICT (code) DO NOTHING이라 반환 1=신규, 0=이미 존재(소진분 포함, 부활 안 함). 트랜잭션은 서비스 소유.
 */
@Repository
public class AdminSignupCodeRepository {

	private final JdbcClient jdbcClient;

	public AdminSignupCodeRepository(JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	public int insert(String code, String channel) {
		return jdbcClient.sql("""
				INSERT INTO app.signup_codes (code, channel) VALUES (:code, :channel)
				ON CONFLICT (code) DO NOTHING""")
				.param("code", code)
				.param("channel", channel)
				.update();
	}
}
```

- [ ] **Step 2:** `./gradlew :was:compileJava` → BUILD SUCCESSFUL
- [ ] **Step 3:** commit `feat(was): 가입 코드 ON CONFLICT 단건 삽입 리포지토리`

---

## Task 3: 예외 + advice (`{"error":...}`)

**Files:** Create `admin/AdminApiException.java`, `admin/AdminApiExceptionAdvice.java`

- [ ] **Step 1: 예외**

```java
package com.celfit.was.admin;

/** admin 쓰기 API 표면 예외(설계 2026-07-20) — status와 message를 그대로 {"error":message}로 렌더. */
public class AdminApiException extends RuntimeException {

	private final int status;

	public AdminApiException(int status, String message) {
		super(message);
		this.status = status;
	}

	public int status() {
		return status;
	}
}
```

- [ ] **Step 2: advice (컨트롤러 스코프 — read 엔드포인트 무영향)**

```java
package com.celfit.was.admin;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 가입 코드 적재 컨트롤러 전용 에러 렌더(설계 2026-07-20) — 어드민이 본문을 그대로 노출하므로 {"error":메시지}.
 * assignableTypes로 AdminSignupCodeController에만 적용(read용 AdminSignupController엔 영향 없음).
 */
@RestControllerAdvice(assignableTypes = AdminSignupCodeController.class)
public class AdminApiExceptionAdvice {

	@ExceptionHandler(AdminApiException.class)
	public ResponseEntity<Map<String, String>> handle(AdminApiException e) {
		return ResponseEntity.status(e.status()).body(Map.of("error", e.getMessage()));
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<Map<String, String>> handleUnreadable(HttpMessageNotReadableException e) {
		return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", "요청 본문을 읽을 수 없습니다."));
	}
}
```

- [ ] **Step 3:** 컴파일은 Task 4(컨트롤러) 존재 후 통과 — 이 시점엔 `assignableTypes = AdminSignupCodeController.class`가 미해결이라 **Task 4와 함께 컴파일**한다. 커밋은 Task 4에서 함께.

---

## Task 4: 서비스 + 컨트롤러

**Files:** Create `admin/AdminSignupCodeService.java`, `admin/AdminSignupCodeController.java`

- [ ] **Step 1: 서비스 (검증 → channel 유도 → 삽입 → 집계)**

```java
package com.celfit.was.admin;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 가입 코드 일괄 적재(설계 2026-07-20) — 검증을 먼저 전부 통과시킨 뒤 삽입(부분 저장 없음).
 * channel은 코드 접두사(첫 '-' 앞)에서 유도, 접두사 없으면 400. 중복은 리포지토리 ON CONFLICT가 스킵.
 */
@Service
public class AdminSignupCodeService {

	private static final int MAX_BATCH = 500;
	// 접두사·서픽스 모두 non-empty, 공백/추가 '-' 불가 — 접두사 없는 코드(-XXXX, XXXX) 거부
	private static final Pattern CODE = Pattern.compile("^[^\\s-]+-[^\\s-]+$");

	private final AdminSignupCodeRepository repository;

	public AdminSignupCodeService(AdminSignupCodeRepository repository) {
		this.repository = repository;
	}

	@Transactional
	public SignupCodeCreateResponse create(SignupCodeCreateRequest request) {
		List<String> raw = request == null ? null : request.codes();
		if (raw == null || raw.isEmpty()) {
			throw new AdminApiException(400, "codes가 비어 있습니다.");
		}
		if (raw.size() > MAX_BATCH) {
			throw new AdminApiException(400, "배치 최대 " + MAX_BATCH + "개입니다.");
		}
		record CodeChannel(String code, String channel) {
		}
		List<CodeChannel> parsed = new ArrayList<>(raw.size());
		for (String r : raw) {
			String code = r == null ? "" : r.trim();
			if (code.isEmpty()) {
				throw new AdminApiException(400, "빈 코드가 포함돼 있습니다.");
			}
			if (!CODE.matcher(code).matches()) {
				throw new AdminApiException(400, "접두사 없는 코드입니다: " + code);
			}
			parsed.add(new CodeChannel(code, code.substring(0, code.indexOf('-'))));
		}
		int inserted = 0;
		for (CodeChannel cc : parsed) {
			inserted += repository.insert(cc.code(), cc.channel());
		}
		return new SignupCodeCreateResponse(inserted, raw.size() - inserted);
	}
}
```

- [ ] **Step 2: 컨트롤러**

```java
package com.celfit.was.admin;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 가입 코드 일괄 적재 API(설계 2026-07-20) — POST /admin/signup-codes.
 * 인증은 SecurityConfig의 @Order(0) 토큰 체인(Bearer CODES_API_KEY)이 담당. 검증·저장은 서비스로 위임.
 */
@RestController
public class AdminSignupCodeController {

	private final AdminSignupCodeService service;

	public AdminSignupCodeController(AdminSignupCodeService service) {
		this.service = service;
	}

	@PostMapping("/admin/signup-codes")
	public SignupCodeCreateResponse create(@RequestBody SignupCodeCreateRequest request) {
		return service.create(request);
	}
}
```

- [ ] **Step 3:** `./gradlew :was:compileJava` → BUILD SUCCESSFUL (Task 3 advice 포함 해결)
- [ ] **Step 4:** commit `feat(was): 가입 코드 적재 서비스·컨트롤러·에러 렌더`

---

## Task 5: 토큰 인증 필터

**Files:** Create `admin/CodesApiKeyAuthFilter.java`

- [ ] **Step 1: 작성**

```java
package com.celfit.was.admin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * CODES_API_KEY Bearer 검증 필터(설계 2026-07-20) — /admin/signup-codes 전용 @Order(0) 체인에서만 동작.
 * 키 미설정이면 503(fail-closed·오설정 구분), 토큰 일치 시 인증 세팅(권한 무관). 불일치·헤더 없음이면
 * 인증 미세팅 → 체인의 authenticated()가 진입점 401로 처리. 비교는 MessageDigest.isEqual(상수시간).
 */
public class CodesApiKeyAuthFilter extends OncePerRequestFilter {

	private final String apiKey;

	public CodesApiKeyAuthFilter(String apiKey) {
		this.apiKey = apiKey;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		if (apiKey == null || apiKey.isBlank()) {
			writeError(response, 503, "CODES_API_KEY 미설정");
			return;
		}
		String header = request.getHeader("Authorization");
		if (header != null && header.startsWith("Bearer ")) {
			String token = header.substring("Bearer ".length());
			boolean ok = MessageDigest.isEqual(
					token.getBytes(StandardCharsets.UTF_8), apiKey.getBytes(StandardCharsets.UTF_8));
			if (ok) {
				SecurityContextHolder.getContext().setAuthentication(
						new UsernamePasswordAuthenticationToken("codes-api", null, List.of()));
			}
		}
		chain.doFilter(request, response);
	}

	private void writeError(HttpServletResponse response, int status, String message) throws IOException {
		response.setStatus(status);
		response.setContentType("application/json");
		response.setCharacterEncoding("UTF-8");
		response.getWriter().write("{\"error\":\"" + message + "\"}");
	}
}
```

- [ ] **Step 2:** `./gradlew :was:compileJava` → BUILD SUCCESSFUL
- [ ] **Step 3:** commit `feat(was): CODES_API_KEY Bearer 검증 필터(fail-closed·상수시간)`

---

## Task 6: `@Order(0)` 토큰 체인 + application.yml

**Files:** Modify `config/SecurityConfig.java`, `resources/application.yml`

- [ ] **Step 1: application.yml에 키 바인딩 추가**

파일 루트 최상위(다른 최상위 키와 같은 들여쓰기)에 추가:

```yaml
codes:
  api-key: ${CODES_API_KEY:}   # 어드민 코드 적재용 정적 토큰 — 빈 값이면 /admin/signup-codes 전면 거부(fail-closed)
```

- [ ] **Step 2: SecurityConfig에 @Order(0) 체인 신설**

기존 `@Order(1)` `adminBasicFilterChain` 선언 **바로 위**에 아래 bean과 진입점 정적 클래스를 추가한다.
필요 import: `org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter`,
`com.celfit.was.admin.CodesApiKeyAuthFilter` (그리고 이미 있는 `@Order`, `SessionCreationPolicy`,
`AbstractHttpConfigurer`, `AuthenticationEntryPoint`, `MediaType`, `StandardCharsets`, `HttpStatus` 재사용).

```java
	/**
	 * 가입 코드 적재 전용 체인(설계 2026-07-20) — /admin/signup-codes만 정적 토큰(Bearer CODES_API_KEY)으로 잠근다.
	 * @Order(0)이라 사람용 @Order(1) ADMIN Basic 체인(/admin/**)보다 먼저 이 경로를 잡는다(기계 대 기계 호출).
	 * stateless·CSRF/CORS off, 미인증은 Basic 챌린지 없이 401 {"error":...}. 토큰 검증·fail-closed는 필터가 수행.
	 */
	@Bean
	@Order(0)
	public SecurityFilterChain signupCodeIngestFilterChain(HttpSecurity http,
			@Value("${codes.api-key:}") String codesApiKey) throws Exception {
		http
				.securityMatcher("/admin/signup-codes")
				.authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
				.addFilterBefore(new CodesApiKeyAuthFilter(codesApiKey),
						UsernamePasswordAuthenticationFilter.class)
				.exceptionHandling(ex -> ex.authenticationEntryPoint(new JsonUnauthorizedEntryPoint()))
				.csrf(AbstractHttpConfigurer::disable)
				.cors(AbstractHttpConfigurer::disable)
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
		return http.build();
	}

	/** /admin/signup-codes 미인증 진입점 — Basic 챌린지 없이 401 {"error":...}(어드민이 본문 그대로 노출). */
	static final class JsonUnauthorizedEntryPoint implements AuthenticationEntryPoint {

		@Override
		public void commence(HttpServletRequest request, HttpServletResponse response,
				AuthenticationException authException) throws IOException {
			response.setStatus(HttpStatus.UNAUTHORIZED.value());
			response.setContentType(MediaType.APPLICATION_JSON_VALUE);
			response.setCharacterEncoding(StandardCharsets.UTF_8.name());
			response.getWriter().write("{\"error\":\"인증 실패\"}");
		}
	}
```

- [ ] **Step 3:** `./gradlew :was:compileJava` → BUILD SUCCESSFUL
- [ ] **Step 4:** commit `feat(was): /admin/signup-codes 정적 토큰 체인(@Order(0)) + codes.api-key 바인딩`

---

## Task 7: 통합 테스트 (인증·검증·중복·상한)

**Files:** Create `src/test/java/com/celfit/was/AdminSignupCodeIngestIntegrationTest.java`

`IntegrationTest`(싱글턴 Testcontainers, 무롤백) 상속 + `@AutoConfigureMockMvc`.
키는 `@TestPropertySource(properties = "codes.api-key=test-secret-abc123")`로 주입.
코드는 UUID로 유니크화(공유 DB 재실행 충돌 방지).

- [ ] **Step 1: 작성**

```java
package com.celfit.was;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 가입 코드 일괄 적재(설계 2026-07-20) — /admin/signup-codes 토큰 체인 인증·검증·중복·상한 검증.
 * 싱글턴 공유 DB라 코드는 UUID로 유니크화.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = "codes.api-key=test-secret-abc123")
class AdminSignupCodeIngestIntegrationTest extends IntegrationTest {

	private static final String TOKEN = "test-secret-abc123";

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcClient jdbcClient;

	private String uniqueCode(String prefix) {
		return prefix + "-" + UUID.randomUUID().toString().substring(0, 4).toUpperCase();
	}

	private org.springframework.test.web.servlet.ResultActions submit(String token, String jsonBody) throws Exception {
		var req = post("/admin/signup-codes").contentType(MediaType.APPLICATION_JSON).content(jsonBody);
		if (token != null) {
			req = req.header("Authorization", "Bearer " + token);
		}
		return mockMvc.perform(req);
	}

	@Test
	void 토큰_없으면_401() throws Exception {
		submit(null, "{\"codes\":[\"THREADS-ABCD\"]}")
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error").isNotEmpty());
	}

	@Test
	void 토큰_틀리면_401() throws Exception {
		submit("wrong-token", "{\"codes\":[\"THREADS-ABCD\"]}")
				.andExpect(status().isUnauthorized());
	}

	@Test
	void 정상_적재하면_channel은_접두사에서_유도되고_inserted반환() throws Exception {
		String c1 = uniqueCode("THREADS");
		String c2 = uniqueCode("DM");
		submit(TOKEN, "{\"codes\":[\"" + c1 + "\",\"" + c2 + "\"]}")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.inserted").value(2))
				.andExpect(jsonPath("$.skipped").value(0));
		String channel = jdbcClient.sql("SELECT channel FROM app.signup_codes WHERE code = :c")
				.param("c", c1).query(String.class).single();
		assertThat(channel).isEqualTo("THREADS");
	}

	@Test
	void 기존코드는_스킵되고_신규만_inserted() throws Exception {
		String existing = uniqueCode("THREADS");
		jdbcClient.sql("INSERT INTO app.signup_codes (code, channel) VALUES (:c, 'THREADS')")
				.param("c", existing).update();
		String fresh = uniqueCode("THREADS");
		submit(TOKEN, "{\"codes\":[\"" + existing + "\",\"" + fresh + "\"]}")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.inserted").value(1))
				.andExpect(jsonPath("$.skipped").value(1));
	}

	@Test
	void 접두사없는_코드가_있으면_400이고_아무것도_저장안됨() throws Exception {
		String good = uniqueCode("THREADS");
		submit(TOKEN, "{\"codes\":[\"" + good + "\",\"NOPREFIX\"]}")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").isNotEmpty());
		Long cnt = jdbcClient.sql("SELECT count(*) FROM app.signup_codes WHERE code = :c")
				.param("c", good).query(Long.class).single();
		assertThat(cnt).isZero();
	}

	@Test
	void 빈_배열이면_400() throws Exception {
		submit(TOKEN, "{\"codes\":[]}").andExpect(status().isBadRequest());
	}

	@Test
	void 배치_501개면_400() throws Exception {
		StringBuilder sb = new StringBuilder("{\"codes\":[");
		for (int i = 0; i < 501; i++) {
			if (i > 0) {
				sb.append(',');
			}
			sb.append("\"X-").append(String.format("%04d", i)).append('\"');
		}
		sb.append("]}");
		submit(TOKEN, sb.toString()).andExpect(status().isBadRequest());
	}
}
```

- [ ] **Step 2:** `./gradlew :was:test --tests com.celfit.was.AdminSignupCodeIngestIntegrationTest` → PASS
- [ ] **Step 3:** commit `test(was): 가입 코드 적재 인증·검증·중복·상한 통합 테스트`

---

## Task 8: fail-closed 테스트 (키 미설정 → 503)

**Files:** Create `src/test/java/com/celfit/was/AdminSignupCodeIngestFailClosedTest.java`

빈 키 프로퍼티는 별도 컨텍스트가 필요해 별도 클래스로 분리한다.

- [ ] **Step 1: 작성**

```java
package com.celfit.was;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** CODES_API_KEY 미설정이면 /admin/signup-codes가 503으로 fail-closed(설계 2026-07-20). */
@AutoConfigureMockMvc
@TestPropertySource(properties = "codes.api-key=")
class AdminSignupCodeIngestFailClosedTest extends IntegrationTest {

	@Autowired
	MockMvc mockMvc;

	@Test
	void 키_미설정이면_503() throws Exception {
		mockMvc.perform(post("/admin/signup-codes")
						.header("Authorization", "Bearer anything")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"codes\":[\"THREADS-ABCD\"]}"))
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.error").isNotEmpty());
	}
}
```

- [ ] **Step 2:** `./gradlew :was:test --tests com.celfit.was.AdminSignupCodeIngestFailClosedTest` → PASS
- [ ] **Step 3:** commit `test(was): CODES_API_KEY 미설정 fail-closed(503) 테스트`

---

## Task 9: 전체 테스트 + 문서 반영

**Files:** Modify spec 상태 헤더, `ARCHITECTURE.md` §7

- [ ] **Step 1:** `./gradlew :was:test` → BUILD SUCCESSFUL (무회귀)
- [ ] **Step 2:** 스펙 상태 헤더 `🟢 활성` → `✅ 구현됨(2026-07-20)`
- [ ] **Step 3:** `ARCHITECTURE.md` §7 최상단에 결정 한 줄 추가:
  "가입 코드 일괄 적재 `POST /admin/signup-codes` — 정적 토큰(CODES_API_KEY) @Order(0) 체인(사람용 Basic과 분리), channel 접두사 유도(빈 접두사 400), ON CONFLICT 스킵 후 {inserted,skipped}."
- [ ] **Step 4:** commit `docs: 가입 코드 적재 API 구현 반영(상태·결정 기록)`

---

## 운영 반영(배포 시, 계획 밖)

1. `openssl rand -hex 32`로 토큰 발급 → 운영 was env `CODES_API_KEY` 설정·재기동.
2. 어드민 env `CODES_API_KEY` 동일 값 + `CODES_API_MOCK=false`.
3. 스모크: `curl -H "Authorization: Bearer $CODES_API_KEY" -H 'Content-Type: application/json' -d '{"codes":["SMOKE-TE5T"]}' https://api.hypenow.io/admin/signup-codes` → `{"inserted":1,"skipped":0}`, 후 정리.
