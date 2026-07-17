# 로그인 월 + 가입 코드 구현 계획

> 상태: 🟢 활성
>
> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:executing-plans (인라인 실행).
> 스펙: [specs/2026-07-17-login-wall-signup-code-design.md](../specs/2026-07-17-login-wall-signup-code-design.md)

**Goal:** 모든 조회 표면을 로그인 필수로 잠그고(화이트리스트 전환), 가입에 단일 공용 코드를 요구한다.

**Architecture:** SecurityConfig의 authorizeHttpRequests를 기본 `authenticated()`로 뒤집고 열린 경로 4종만 나열. 가입 코드는 was 소유 `app.app_setting`(V6)에서 매 요청 조회, fail-closed. 레거시 `/api/auth/signup`은 제거.

**Tech Stack:** Spring Security 필터체인, JdbcClient, Flyway(app), MockMvc(@WebMvcTest 슬라이스 + Testcontainers 통합).

---

### Task 1: `app.app_setting` V6 + AppSettingRepository

**Files:**
- Create: `was/src/main/resources/db/migration/app/V6__app_setting.sql`
- Create: `was/src/main/java/com/celfit/was/setting/AppSettingRepository.java`
- Test: `was/src/test/java/com/celfit/was/setting/AppSettingRepositoryTest.java`

- [ ] V6 마이그레이션 (시드는 빈 값 — fail-closed로 시작, 운영자가 UPDATE로 개통):

```sql
-- was 런타임 설정 key-value (raw DB app_setting의 was층 대응 — was는 raw 접근 금지라 별도).
-- 첫 사용: signup.code (가입 코드 — 로그인 월 설계 2026-07-17).
-- 빈 값 시드 = 가입 전면 차단(fail-closed). 개통은 운영자가:
--   UPDATE app.app_setting SET value='<실코드>' WHERE key='signup.code';
CREATE TABLE app.app_setting (
    key   text PRIMARY KEY,
    value text NOT NULL
);

INSERT INTO app.app_setting (key, value) VALUES ('signup.code', '');
```

- [ ] AppSettingRepository (기존 JdbcClient 관용구 — UserRepository 스타일):

```java
package com.celfit.was.setting;

import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** app.app_setting 조회 — 캐시 없이 매번 SELECT(교체 즉시 반영이 요구사항, 호출 빈도 낮음). */
@Repository
public class AppSettingRepository {

	private final JdbcClient jdbcClient;

	public AppSettingRepository(JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	public Optional<String> findValue(String key) {
		return jdbcClient.sql("SELECT value FROM app.app_setting WHERE key = :key")
				.param("key", key)
				.query(String.class)
				.optional();
	}
}
```

- [ ] 테스트(UserRepositoryTest와 같은 베이스 사용 — 실행 시 해당 파일 패턴 확인 후 동일하게):
  시드 행 존재(`signup.code` → 빈 문자열), 없는 키 → empty Optional. UPDATE 후 재조회 시 새 값.
- [ ] `./gradlew :was:test --tests '*AppSettingRepositoryTest'` 그린 → 커밋 `feat(was): app.app_setting V6 + 조회 리포지토리`

### Task 2: 가입 코드 검증 (`/v1/auth/signup`)

**Files:**
- Modify: `was/src/main/java/com/celfit/was/v1/common/V1ApiException.java` (forbidden 팩토리)
- Modify: `was/src/main/java/com/celfit/was/v1/account/SignupRequest.java` (signupCode 필드)
- Modify: `was/src/main/java/com/celfit/was/v1/account/V1AuthController.java`
- Test: `was/src/test/java/com/celfit/was/v1/account/V1AuthControllerTest.java`

- [ ] V1ApiException에 추가:

```java
	public static V1ApiException forbidden(String code, String message) {
		return new V1ApiException(HttpStatus.FORBIDDEN, code, message);
	}
```

- [ ] SignupRequest 첫 컴포넌트로 `String signupCode` 추가(toNewUser는 무변경).
- [ ] V1AuthController: `AppSettingRepository` 주입 + slf4j Logger 추가, signup에서 레이트리밋 직후 호출:

```java
	/** 가입 코드 대조(스펙 07-17) — 미설정·빈 값이면 전면 차단(fail-closed), 불일치와 같은 403. */
	private void verifySignupCode(String submitted) {
		String required = appSettingRepository.findValue("signup.code")
				.map(String::trim).filter(s -> !s.isEmpty()).orElse(null);
		if (required == null) {
			log.error("app_setting signup.code 미설정 — 가입 전면 차단(fail-closed)");
			throw V1ApiException.forbidden("INVALID_SIGNUP_CODE", "가입 코드를 확인해 주세요.");
		}
		if (submitted == null || !required.equals(submitted.trim())) {
			throw V1ApiException.forbidden("INVALID_SIGNUP_CODE", "가입 코드를 확인해 주세요.");
		}
	}
```

- [ ] 테스트 먼저: V1AuthControllerTest에 `@MockitoBean AppSettingRepository` 추가,
  기존 정상 케이스는 `given(...findValue("signup.code")).willReturn(Optional.of("BETA2026"))` +
  VALID_SIGNUP_BODY에 `"signupCode":"BETA2026"` 추가. 신규 3케이스:
  ① 코드 불일치 → 403 + `$.error.code`=INVALID_SIGNUP_CODE ② 미설정(Optional.empty) → 403
  ③ 빈 값 설정(`Optional.of("")`) → 403. 실패 확인 → 구현 → 그린.
- [ ] `./gradlew :was:test --tests '*V1AuthControllerTest'` 그린 → 커밋 `feat(was): 가입 코드 검증 — 403 INVALID_SIGNUP_CODE·fail-closed`

### Task 3: 로그인 월 — SecurityConfig 화이트리스트 전환

**Files:**
- Modify: `was/src/main/java/com/celfit/was/config/SecurityConfig.java:78-83`
- Test: `was/src/test/java/com/celfit/was/LoginWallIntegrationTest.java` (신규)

- [ ] authorizeHttpRequests 교체:

```java
				.authorizeHttpRequests(auth -> auth
						// 로그인 월(07-17 설계) — 기본 잠금, 열린 경로만 나열(화이트리스트).
						// 새 엔드포인트는 기본이 잠김이라 실수로 새지 않는다.
						.requestMatchers("/v1/auth/**").permitAll()      // 인증 입구(레거시 /api/auth는 잠금 — /v1로 일원화)
						.requestMatchers("/v1/events/gate").permitAll()  // 익명 게이트 측정 유지(스펙 6.19)
						.requestMatchers("/health").permitAll()          // 배포 헬스체크(익명 curl)
						.requestMatchers("/swagger-ui/**", "/v3/api-docs/**").permitAll() // 로컬·개발 문서(prod는 springdoc 비활성)
						.anyRequest().authenticated())
```

- [ ] LoginWallIntegrationTest (IntegrationTest 상속 + @AutoConfigureMockMvc) — 통합으로 검증해야
  필터체인·엔트리포인트가 실물이다. 익명 요청이 필터 단계에서 끊기므로 분석 테이블 부재와 무관:

```java
	// 잠긴 경로: /v1 읽기 → 401 envelope
	mockMvc.perform(get("/v1/contents?startDate=2026-06-01&endDate=2026-07-17"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
	// 잠긴 경로: 내부 페이지·구 /api·프로필 이미지 → 401 (빈 본문)
	get("/") · get("/coverage") · get("/api/contents") · get("/profile-images/x.png") → isUnauthorized()
	// 열린 경로: /health 200, POST /v1/events/gate(익명·CSRF무) 204,
	// POST /v1/auth/login(잘못된 자격, csrf()) → 401 INVALID_CREDENTIALS (월이 아니라 인증 로직에 도달했다는 증거)
	// 로그인 후: /v1 읽기가 401이 아님(테스트 컨테이너에 분석 테이블이 없어 500 — 월 통과만 확인)
```

- [ ] `./gradlew :was:test --tests '*LoginWallIntegrationTest'` 그린(다른 테스트는 Task 5까지 빨간 상태 허용)
  → 커밋 `feat(was): 로그인 월 — 화이트리스트 전환, 전 조회 표면 인증 필수`

### Task 4: 레거시 `/api/auth/signup` 폐쇄

**Files:**
- Modify: `was/src/main/java/com/celfit/was/auth/AuthController.java` (signup 메서드 삭제)
- Delete(사용처 없으면): `was/src/main/java/com/celfit/was/auth/SignupRequest.java`, `UserRepository.insert(email, hash)`
- Modify/Delete: `was/src/test/java/com/celfit/was/AuthFlowIntegrationTest.java`

- [ ] AuthController.signup 삭제. auth.SignupRequest·UserRepository.insert는 grep으로 사용처 확인 후 미사용이면 삭제.
- [ ] AuthFlowIntegrationTest는 레거시 표면(/api/auth·/api/me) 계약 테스트 — 월 이후 레거시는 전부 잠겨
  죽은 표면이므로 **삭제**하고, 세션·CSRF의 실질 커버리지는 v1 통합 테스트(SessionPersistence·CsrfCookieFlow,
  Task 5에서 /v1/auth 경로로 전환)가 승계한다.
- [ ] 커밋 `feat(was): 레거시 /api/auth/signup 폐쇄 — 가입 입구 /v1 일원화`

### Task 5: 기존 테스트 일괄 보강 (가장 잔손 많음)

**Files:** 슬라이스 16개 중 잠긴 경로를 치는 전부 + 통합 테스트 3종.

- [ ] **슬라이스(읽기 컨트롤러)**: contentlist·postdetail·postdemo·coverage·influencer(구)·
  v1/content×2·v1/influencer×2·V1ExceptionAdviceTest — 익명 GET이 401을 맞으므로 인증 주입.
  - 컨트롤러가 `@AuthenticationPrincipal AppUserDetails`를 쓰면(개인화 필드) 반드시
    `.with(user(실제 AppUserDetails 인스턴스))`, 아니면 클래스 레벨 `@WithMockUser`.
    실행 시 각 컨트롤러의 principal 사용 여부를 grep으로 확인하고 케이스별 적용.
  - 기존 "익명도 200" 성격의 단언이 있으면 "익명 401" 단언으로 의미를 뒤집어 보존.
- [ ] **V1GateEventControllerTest**: 익명 그대로 통과해야 한다 — 수정 없이 그린인지만 확인(월 회귀 가드).
- [ ] **통합 테스트**: SessionPersistence·CsrfCookieFlow·SavedApi가 레거시 /api/auth/login으로 세션을 만들면
  /v1/auth/login(+signupCode 가입이 필요하면 app_setting 시드 후 /v1/auth/signup)으로 전환.
  CSRF 부트스트랩(익명 GET에서 XSRF-TOKEN 수신)은 이제 401 응답에서 일어난다 — CsrfFilter가
  AuthorizationFilter보다 앞이라 쿠키는 여전히 내려온다. 단언을 200→401로 조정하되 쿠키 수신은 그대로 검증.
- [ ] `./gradlew :was:test` 전체 그린 → 커밋 `test(was): 로그인 월 반영 — 슬라이스 인증 주입·통합 로그인 /v1 전환`

### Task 6: 문서·전체 검증·PR

- [ ] ARCHITECTURE §3 app 스키마 표에 `app_setting` 추가, §7 결정 기록 1행(로그인 월+가입 코드, 스펙 링크).
- [ ] 스펙 상태 헤더 → `✅ 구현/실행/반영됨`. 계획 문서 → `plans/archive/`로 이동.
- [ ] `./gradlew build` 전체 그린 + bootRun 실기동으로 익명 401/로그인 후 200/코드 가입 실검증.
- [ ] push → develop 대상 PR(운영 절차 — app_setting UPDATE·프론트 signupCode 배포 순서 — 본문에 명시).

## Self-Review 체크

- 스펙 §1(월·엔트리포인트·레거시 폐쇄)=Task 3·4, §2(코드·V6·fail-closed)=Task 1·2, §3(테스트)=Task 3·5, §4(운영)=Task 6 PR 본문. 커버 완료.
- 타입 일관성: `AppSettingRepository.findValue(String): Optional<String>` — Task 1 정의·Task 2 사용 일치.
- 실행 중 확인 필요로 남긴 것(placeholder 아님, 분기 명시): 리포지토리 테스트 베이스 패턴,
  각 컨트롤러의 principal 사용 여부(→ user(AppUserDetails) vs @WithMockUser 분기).
