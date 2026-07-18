# 이메일 소유권 인증 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 가입 전 이메일 소유권 인증(6자리 코드·Resend 발송·서버 상태 방식)을 was에 구현해 스펙 6.17 [TBD]를 해소한다.

**Architecture:** `app.email_verifications`(V7) 1테이블 + `/v1/auth/email-verification/{send,confirm}` 2엔드포인트 + signup에 403 `EMAIL_NOT_VERIFIED` 게이트 삽입. 발송은 `mail` 패키지의 `MailSender` 포트(Resend HTTPS API / 키 미설정 시 로깅 폴백). 스펙: [specs/2026-07-18-email-verification-design.md](../specs/2026-07-18-email-verification-design.md).

**Tech Stack:** Spring Boot 4.1(Java 21) · JdbcClient · Flyway(app 스키마, was 소유) · RestClient(Resend) · Testcontainers/MockMvc. 신규 외부 의존성 없음.

**브랜치:** `feat/email-verification` (스펙 커밋 위에서 계속) · PR 대상 `develop`

**전제 지식:**
- 기존 v1 에러 계약: `V1ApiException`(status+code+message) → `V1ExceptionAdvice`가 envelope 변환. 기가입 409 코드는 `EMAIL_ALREADY_EXISTS`(기존 signup과 동일해야 함).
- `RateLimiter`는 분당 고정 윈도우·전 키 공통 상한(기본 10) — 이번에 키별 상한 오버로드를 추가한다.
- 통합 테스트는 `IntegrationTest`(Testcontainers postgres 싱글턴) + MockMvc + `V1AuthTestSteps`(가입 코드 개통·가입 스텝). **가입 전 강제가 들어가면 기존의 모든 가입 경유 테스트가 깨지므로 Task 6에서 시드 헬퍼로 일괄 갱신한다.**
- 주석·커밋 메시지는 한국어, 탭 인덴트.

---

### Task 1: V7 마이그레이션 + EmailVerificationRepository

**Files:**
- Create: `was/src/main/resources/db/migration/app/V7__email_verifications.sql`
- Create: `was/src/main/java/com/celfit/was/v1/account/EmailVerificationRepository.java`
- Test: `was/src/test/java/com/celfit/was/v1/account/EmailVerificationRepositoryTest.java`

- [ ] **Step 1: 마이그레이션 작성**

```sql
-- 이메일 소유권 인증(설계 2026-07-18) — 가입 전 강제. 이메일당 1행.
-- 재발송은 upsert(코드 재생성·attempts 리셋·verified_at 초기화 — 마지막 발송만 유효),
-- 가입 성공 직후 행 삭제(1회 소비). 주 방어선은 TTL 10분 + 오입력 5회 + 레이트리밋.
CREATE TABLE app.email_verifications (
    email           text PRIMARY KEY,
    code_hash       text NOT NULL,
    code_expires_at timestamptz NOT NULL,
    attempts        int NOT NULL DEFAULT 0,
    verified_at     timestamptz,
    created_at      timestamptz NOT NULL DEFAULT now()
);
```

- [ ] **Step 2: 실패하는 리포지토리 테스트 작성**

```java
package com.celfit.was.v1.account;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.was.IntegrationTest;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

class EmailVerificationRepositoryTest extends IntegrationTest {

	@Autowired
	EmailVerificationRepository repository;

	@Autowired
	JdbcClient jdbcClient;

	static final String EMAIL = "repo-test@example.com";

	@BeforeEach
	void clean() {
		jdbcClient.sql("DELETE FROM app.email_verifications").update();
	}

	@Test
	void upsert_후_find로_행을_읽는다() {
		Instant expiresAt = Instant.now().plusSeconds(600);
		repository.upsert(EMAIL, "hash-1", expiresAt);

		var row = repository.find(EMAIL).orElseThrow();
		assertThat(row.email()).isEqualTo(EMAIL);
		assertThat(row.codeHash()).isEqualTo("hash-1");
		assertThat(row.attempts()).isZero();
		assertThat(row.verifiedAt()).isNull();
		assertThat(row.codeExpiresAt()).isCloseTo(expiresAt, org.assertj.core.api.Assertions.within(1, java.time.temporal.ChronoUnit.SECONDS));
	}

	@Test
	void 재발송_upsert는_코드를_교체하고_attempts와_verified를_리셋한다() {
		repository.upsert(EMAIL, "hash-1", Instant.now().plusSeconds(600));
		repository.incrementAttempts(EMAIL);
		repository.markVerified(EMAIL, Instant.now());

		repository.upsert(EMAIL, "hash-2", Instant.now().plusSeconds(600));

		var row = repository.find(EMAIL).orElseThrow();
		assertThat(row.codeHash()).isEqualTo("hash-2");
		assertThat(row.attempts()).isZero();
		assertThat(row.verifiedAt()).isNull();
	}

	@Test
	void markVerified와_delete가_동작한다() {
		repository.upsert(EMAIL, "hash-1", Instant.now().plusSeconds(600));
		Instant verifiedAt = Instant.now();
		repository.markVerified(EMAIL, verifiedAt);
		assertThat(repository.find(EMAIL).orElseThrow().verifiedAt())
				.isCloseTo(verifiedAt, org.assertj.core.api.Assertions.within(1, java.time.temporal.ChronoUnit.SECONDS));

		repository.delete(EMAIL);
		assertThat(repository.find(EMAIL)).isEmpty();
	}

	@Test
	void incrementAttempts는_1씩_올린다() {
		repository.upsert(EMAIL, "hash-1", Instant.now().plusSeconds(600));
		repository.incrementAttempts(EMAIL);
		repository.incrementAttempts(EMAIL);
		assertThat(repository.find(EMAIL).orElseThrow().attempts()).isEqualTo(2);
	}
}
```

- [ ] **Step 3: 실패 확인**

Run: `./gradlew :was:test --tests 'com.celfit.was.v1.account.EmailVerificationRepositoryTest'`
Expected: 컴파일 실패 — `EmailVerificationRepository` 심볼 없음

- [ ] **Step 4: 리포지토리 구현**

```java
package com.celfit.was.v1.account;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** app.email_verifications 접근 — 이메일당 1행. 재발송 upsert·인증 마킹·가입 시 소비(삭제). */
@Repository
public class EmailVerificationRepository {

	public record Verification(String email, String codeHash, Instant codeExpiresAt,
			int attempts, Instant verifiedAt) {
	}

	private final JdbcClient jdbcClient;

	public EmailVerificationRepository(JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	/** 발송 성공 후에만 호출 — 기존 행이 있으면 코드 교체 + attempts·verified_at 리셋(마지막 발송만 유효). */
	public void upsert(String email, String codeHash, Instant codeExpiresAt) {
		jdbcClient.sql("""
				INSERT INTO app.email_verifications (email, code_hash, code_expires_at)
				VALUES (:email, :codeHash, :codeExpiresAt)
				ON CONFLICT (email) DO UPDATE
				SET code_hash = EXCLUDED.code_hash, code_expires_at = EXCLUDED.code_expires_at,
				    attempts = 0, verified_at = NULL, created_at = now()""")
				.param("email", email)
				.param("codeHash", codeHash)
				.param("codeExpiresAt", OffsetDateTime.ofInstant(codeExpiresAt, ZoneOffset.UTC))
				.update();
	}

	public Optional<Verification> find(String email) {
		return jdbcClient.sql("""
				SELECT email, code_hash, code_expires_at, attempts, verified_at
				FROM app.email_verifications WHERE email = :email""")
				.param("email", email)
				.query((rs, rowNum) -> new Verification(
						rs.getString("email"),
						rs.getString("code_hash"),
						rs.getTimestamp("code_expires_at").toInstant(),
						rs.getInt("attempts"),
						rs.getTimestamp("verified_at") == null ? null : rs.getTimestamp("verified_at").toInstant()))
				.optional();
	}

	/** 해시 불일치 오입력 카운트 — 만료·부재는 세지 않는다(서비스 판정 순서 참조). */
	public void incrementAttempts(String email) {
		jdbcClient.sql("UPDATE app.email_verifications SET attempts = attempts + 1 WHERE email = :email")
				.param("email", email)
				.update();
	}

	public void markVerified(String email, Instant verifiedAt) {
		jdbcClient.sql("UPDATE app.email_verifications SET verified_at = :verifiedAt WHERE email = :email")
				.param("verifiedAt", OffsetDateTime.ofInstant(verifiedAt, ZoneOffset.UTC))
				.param("email", email)
				.update();
	}

	/** 가입 성공 직후 1회 소비 — 잔존해도 verified 30분 만료로 무해. */
	public void delete(String email) {
		jdbcClient.sql("DELETE FROM app.email_verifications WHERE email = :email")
				.param("email", email)
				.update();
	}
}
```

- [ ] **Step 5: 통과 확인**

Run: `./gradlew :was:test --tests 'com.celfit.was.v1.account.EmailVerificationRepositoryTest'`
Expected: PASS (V7 마이그레이션 자동 적용 포함)

- [ ] **Step 6: 커밋**

```bash
git add was/src/main/resources/db/migration/app/V7__email_verifications.sql \
        was/src/main/java/com/celfit/was/v1/account/EmailVerificationRepository.java \
        was/src/test/java/com/celfit/was/v1/account/EmailVerificationRepositoryTest.java
git commit -m "feat(was): 이메일 인증 저장 계층 — app.email_verifications(V7) + 리포지토리"
```

---

### Task 2: RateLimiter 키별 상한 오버로드

send(이메일 분당 1·IP 분당 5)·confirm(IP 분당 10)은 기본 상한(10)과 다르다. 기존 단일 상한 메서드를 위임 구조로 바꾼다.

**Files:**
- Modify: `was/src/main/java/com/celfit/was/v1/account/RateLimiter.java` (tryAcquire)
- Test: `was/src/test/java/com/celfit/was/v1/account/RateLimiterTest.java` (테스트 추가)

- [ ] **Step 1: 실패하는 테스트 추가** — 기존 `RateLimiterTest`에 아래 테스트를 추가한다(기존 테스트의 RateLimiter 생성 관용구가 아래와 다르면 그 관용구를 따를 것):

```java
	@Test
	void 키별_상한_오버라이드_초과시_거부() {
		RateLimiter limiter = new RateLimiter(
				java.time.Clock.fixed(java.time.Instant.EPOCH, java.time.ZoneOffset.UTC), 10);
		assertThat(limiter.tryAcquire("send:a@b.c", 1)).isTrue();
		assertThat(limiter.tryAcquire("send:a@b.c", 1)).isFalse();
		// 다른 키는 독립
		assertThat(limiter.tryAcquire("send:x@y.z", 1)).isTrue();
	}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :was:test --tests 'com.celfit.was.v1.account.RateLimiterTest'`
Expected: 컴파일 실패 — `tryAcquire(String, int)` 없음

- [ ] **Step 3: 오버로드 구현** — 기존 `tryAcquire(String)`의 본문을 옮기고 위임한다:

```java
	/** 허용되면 true. 분이 바뀌면 카운터 리셋(고정 윈도우). 기본 상한(was.rate-limit.per-minute). */
	public boolean tryAcquire(String key) {
		return tryAcquire(key, perMinute);
	}

	/** 경로별 상한이 다른 경우(이메일 인증 발송 분당 1회 등) — 윈도우 구조는 공유, 상한만 오버라이드. */
	public boolean tryAcquire(String key, int limit) {
		long minute = clock.instant().getEpochSecond() / 60;
		sweepIfMinuteChanged(minute);
		Window w = windows.compute(key, (k, old) ->
				(old == null || old.epochMinute() != minute) ? new Window(minute, new AtomicInteger()) : old);
		return w.count().incrementAndGet() <= limit;
	}
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew :was:test --tests 'com.celfit.was.v1.account.RateLimiterTest'`
Expected: PASS (기존 테스트 포함 전부)

- [ ] **Step 5: 커밋**

```bash
git add was/src/main/java/com/celfit/was/v1/account/RateLimiter.java \
        was/src/test/java/com/celfit/was/v1/account/RateLimiterTest.java
git commit -m "feat(was): RateLimiter 키별 상한 오버로드 — 이메일 인증 경로별 한도 대비"
```

---

### Task 3: V1ApiException 팩토리 2종 + SignupValidator 이메일 검사 추출

`INVALID_CODE`(400 커스텀 코드)와 `EMAIL_SEND_FAILED`(502)를 위한 팩토리, 그리고 send 엔드포인트가 재사용할 이메일 형식 검사를 추출한다. 순수 리팩토링+추가라 기존 테스트로 회귀 확인.

**Files:**
- Modify: `was/src/main/java/com/celfit/was/v1/common/V1ApiException.java`
- Modify: `was/src/main/java/com/celfit/was/v1/account/SignupValidator.java`

- [ ] **Step 1: V1ApiException에 팩토리 추가** — `forbidden` 아래에:

```java
	/** 코드 지정 400 — validation()의 고정 코드(VALIDATION_FAILED)와 달리 계약 코드를 직접 든다(예: INVALID_CODE). */
	public static V1ApiException badRequest(String code, String message) {
		return new V1ApiException(HttpStatus.BAD_REQUEST, code, message);
	}

	/** 외부 의존(메일 발송 등) 실패 — 502. */
	public static V1ApiException badGateway(String code, String message) {
		return new V1ApiException(HttpStatus.BAD_GATEWAY, code, message);
	}
```

- [ ] **Step 2: SignupValidator에서 이메일 검사 추출** — `validate()`의 이메일 검사 첫 3줄을 교체하고 공개 메서드로 추출:

```java
	public void validate(SignupRequest request) {
		requireEmail(request.email());
		validatePassword(request.password());
		// ... (이하 기존 그대로)
```

```java
	/** 이메일 인증 발송(send) 재사용 — 가입과 동일한 형식 검사. */
	public void requireEmail(String email) {
		if (email == null || !EMAIL_PATTERN.matcher(email).matches()) {
			throw V1ApiException.validation("올바른 이메일 형식을 입력해 주세요.");
		}
	}
```

- [ ] **Step 3: 회귀 확인**

Run: `./gradlew :was:test --tests 'com.celfit.was.v1.account.*'`
Expected: PASS

- [ ] **Step 4: 커밋**

```bash
git add was/src/main/java/com/celfit/was/v1/common/V1ApiException.java \
        was/src/main/java/com/celfit/was/v1/account/SignupValidator.java
git commit -m "feat(was): V1ApiException badRequest·badGateway 팩토리 + 이메일 형식 검사 추출"
```

---

### Task 4: mail 패키지 — MailSender 포트 + Resend/Logging 어댑터

**Files:**
- Create: `was/src/main/java/com/celfit/was/mail/MailSender.java`
- Create: `was/src/main/java/com/celfit/was/mail/MailSendException.java`
- Create: `was/src/main/java/com/celfit/was/mail/LoggingMailSender.java`
- Create: `was/src/main/java/com/celfit/was/mail/ResendMailSender.java`
- Create: `was/src/main/java/com/celfit/was/mail/MailConfig.java`
- Modify: `was/src/main/resources/application.yml` (`was:` 블록에 mail 추가)
- Test: `was/src/test/java/com/celfit/was/mail/ResendMailSenderTest.java`

- [ ] **Step 1: 실패하는 ResendMailSender 테스트 작성**

```java
package com.celfit.was.mail;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class ResendMailSenderTest {

	@Test
	void 성공_응답이면_예외없이_발송된다() {
		RestClient.Builder builder = RestClient.builder().baseUrl("https://api.resend.com")
				.defaultHeader("Authorization", "Bearer test-key");
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		server.expect(requestTo("https://api.resend.com/emails"))
				.andExpect(header("Authorization", "Bearer test-key"))
				.andExpect(jsonPath("$.to[0]").value("a@b.c"))
				.andExpect(jsonPath("$.subject").value("제목"))
				.andRespond(withSuccess("{\"id\":\"re_1\"}", MediaType.APPLICATION_JSON));

		ResendMailSender sender = new ResendMailSender(builder.build(), "hypenow <no-reply@hypenow.io>");
		assertThatCode(() -> sender.send("a@b.c", "제목", "본문")).doesNotThrowAnyException();
		server.verify();
	}

	@Test
	void 비2xx_응답이면_MailSendException() {
		RestClient.Builder builder = RestClient.builder().baseUrl("https://api.resend.com");
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		server.expect(requestTo("https://api.resend.com/emails"))
				.andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

		ResendMailSender sender = new ResendMailSender(builder.build(), "hypenow <no-reply@hypenow.io>");
		assertThatThrownBy(() -> sender.send("a@b.c", "제목", "본문"))
				.isInstanceOf(MailSendException.class);
	}
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :was:test --tests 'com.celfit.was.mail.ResendMailSenderTest'`
Expected: 컴파일 실패 — mail 패키지 심볼 없음

- [ ] **Step 3: 포트·예외·어댑터 구현**

`MailSender.java`:

```java
package com.celfit.was.mail;

/** 트랜잭션 메일 발송 포트 — 실패는 MailSendException(호출측이 502 EMAIL_SEND_FAILED로 변환). */
public interface MailSender {

	void send(String to, String subject, String text);
}
```

`MailSendException.java`:

```java
package com.celfit.was.mail;

/** 메일 발송 실패(Resend 비2xx·타임아웃 등). */
public class MailSendException extends RuntimeException {

	public MailSendException(String message) {
		super(message);
	}

	public MailSendException(String message, Throwable cause) {
		super(message, cause);
	}
}
```

`LoggingMailSender.java`:

```java
package com.celfit.was.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** RESEND_API_KEY 미설정 시 대체 — 발송 대신 내용을 로그로 출력(로컬 개발·통합 테스트용). */
public class LoggingMailSender implements MailSender {

	private static final Logger log = LoggerFactory.getLogger(LoggingMailSender.class);

	@Override
	public void send(String to, String subject, String text) {
		log.info("메일 발송(로깅 모드) to={} subject={} text={}", to, subject, text);
	}
}
```

`ResendMailSender.java`:

```java
package com.celfit.was.mail;

import java.util.Map;
import org.springframework.web.client.RestClient;

/**
 * Resend HTTPS API 발송(POST /emails) — SMTP 불사용이라 오라클 아웃바운드 25포트 차단과 무관.
 * 비2xx·네트워크 오류는 MailSendException으로 감싼다.
 */
public class ResendMailSender implements MailSender {

	private final RestClient restClient;
	private final String from;

	public ResendMailSender(RestClient restClient, String from) {
		this.restClient = restClient;
		this.from = from;
	}

	@Override
	public void send(String to, String subject, String text) {
		try {
			restClient.post().uri("/emails")
					.body(Map.of("from", from, "to", new String[] {to}, "subject", subject, "text", text))
					.retrieve()
					.toBodilessEntity();
		} catch (RuntimeException e) {
			throw new MailSendException("Resend 발송 실패: " + e.getMessage(), e);
		}
	}
}
```

`MailConfig.java`:

```java
package com.celfit.was.mail;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * 발송 구현 선택 — was.mail.resend-api-key가 비어 있으면 LoggingMailSender(로컬·테스트),
 * 있으면 ResendMailSender. 프로파일 분기 대신 키 유무 단일 기준(설정 실수여도 부팅은 된다).
 */
@Configuration
public class MailConfig {

	@Bean
	MailSender mailSender(@Value("${was.mail.resend-api-key:}") String apiKey,
			@Value("${was.mail.from:hypenow <no-reply@hypenow.io>}") String from) {
		if (apiKey == null || apiKey.isBlank()) {
			return new LoggingMailSender();
		}
		RestClient restClient = RestClient.builder()
				.baseUrl("https://api.resend.com")
				.defaultHeader("Authorization", "Bearer " + apiKey)
				.build();
		return new ResendMailSender(restClient, from);
	}
}
```

- [ ] **Step 4: application.yml의 `was:` 블록에 mail 설정 추가** (`cors:` 형제 위치):

```yaml
  mail:
    resend-api-key: ${RESEND_API_KEY:}    # 빈 값 → LoggingMailSender(발송 대신 로그 — 로컬·테스트)
    from: "hypenow <no-reply@hypenow.io>" # Resend 도메인 인증(hypenow.io SPF/DKIM) 필요 — 운영 체크리스트
```

- [ ] **Step 5: 통과 확인**

Run: `./gradlew :was:test --tests 'com.celfit.was.mail.ResendMailSenderTest'`
Expected: PASS

- [ ] **Step 6: 커밋**

```bash
git add was/src/main/java/com/celfit/was/mail/ was/src/main/resources/application.yml \
        was/src/test/java/com/celfit/was/mail/ResendMailSenderTest.java
git commit -m "feat(was): 메일 발송 포트 — Resend HTTPS 어댑터 + 키 미설정 로깅 폴백"
```

---

### Task 5: EmailVerificationService + send/confirm 엔드포인트

**Files:**
- Create: `was/src/main/java/com/celfit/was/v1/account/EmailVerificationService.java`
- Create: `was/src/main/java/com/celfit/was/v1/account/V1EmailVerificationController.java`
- Test: `was/src/test/java/com/celfit/was/EmailVerificationIntegrationTest.java`

- [ ] **Step 1: 실패하는 통합 테스트 작성** (send/confirm 표면 — signup 게이트는 Task 6에서 추가)

```java
package com.celfit.was;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.celfit.was.mail.MailSendException;
import com.celfit.was.mail.MailSender;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 이메일 소유권 인증(설계 2026-07-18) 통합 테스트 — RecordingMailSender로 발송 내용을 가로채
 * 코드를 캡처한다. 레이트리밋(이메일 분당 1회)이 전역 싱글턴이라 테스트마다 고유 이메일을 쓴다.
 */
@AutoConfigureMockMvc
class EmailVerificationIntegrationTest extends IntegrationTest {

	@TestConfiguration
	static class MailTestConfig {

		@Bean
		@Primary
		RecordingMailSender recordingMailSender() {
			return new RecordingMailSender();
		}
	}

	static class RecordingMailSender implements MailSender {

		final List<String> texts = new ArrayList<>();
		boolean failNext;

		@Override
		public void send(String to, String subject, String text) {
			if (failNext) {
				failNext = false;
				throw new MailSendException("주입된 실패");
			}
			texts.add(text);
		}

		String lastCode() {
			Matcher m = Pattern.compile("\\d{6}").matcher(texts.get(texts.size() - 1));
			if (!m.find()) {
				throw new IllegalStateException("발송 본문에 6자리 코드 없음: " + texts);
			}
			return m.group();
		}
	}

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcClient jdbcClient;

	@Autowired
	RecordingMailSender mail;

	@BeforeEach
	void setUp() {
		V1AuthTestSteps.enableSignupCode(jdbcClient);
		jdbcClient.sql("DELETE FROM app.email_verifications").update();
		mail.texts.clear();
		mail.failNext = false;
	}

	private void send(String email, int expectedStatus) throws Exception {
		mockMvc.perform(post("/v1/auth/email-verification/send").with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"email\":\"%s\"}".formatted(email)))
				.andExpect(status().is(expectedStatus));
	}

	private void confirm(String email, String code, int expectedStatus) throws Exception {
		mockMvc.perform(post("/v1/auth/email-verification/confirm").with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"email\":\"%s\",\"code\":\"%s\"}".formatted(email, code)))
				.andExpect(status().is(expectedStatus));
	}

	@Test
	void 발송_확인_해피패스_verified_마킹() throws Exception {
		send("verify-happy@example.com", 204);
		confirm("verify-happy@example.com", mail.lastCode(), 204);

		Boolean verified = jdbcClient.sql(
						"SELECT verified_at IS NOT NULL FROM app.email_verifications WHERE email = 'verify-happy@example.com'")
				.query(Boolean.class).single();
		assertThat(verified).isTrue();
	}

	@Test
	void 형식_오류는_400() throws Exception {
		send("not-an-email", 400);
	}

	@Test
	void 같은_이메일_분당_2회_발송은_429() throws Exception {
		send("verify-cooldown@example.com", 204);
		send("verify-cooldown@example.com", 429);
	}

	@Test
	void 오입력_5회_초과시_정답도_거부() throws Exception {
		send("verify-attempts@example.com", 204);
		String code = mail.lastCode();
		String wrong = code.equals("000000") ? "000001" : "000000";
		for (int i = 0; i < 5; i++) {
			confirm("verify-attempts@example.com", wrong, 400);
		}
		confirm("verify-attempts@example.com", code, 400);
	}

	@Test
	void 만료된_코드는_400() throws Exception {
		send("verify-expired@example.com", 204);
		jdbcClient.sql("""
				UPDATE app.email_verifications SET code_expires_at = now() - interval '1 minute'
				WHERE email = 'verify-expired@example.com'""").update();
		confirm("verify-expired@example.com", mail.lastCode(), 400);
	}

	@Test
	void 발송_이력_없는_이메일_confirm은_400() throws Exception {
		confirm("verify-none@example.com", "123456", 400);
	}

	@Test
	void 기가입_이메일_발송은_409() throws Exception {
		// V1AuthTestSteps.signUp은 Task 6에서 (mockMvc, jdbcClient, email) 시그니처가 된다
		V1AuthTestSteps.signUp(mockMvc, jdbcClient, "verify-dup@example.com");
		send("verify-dup@example.com", 409);
	}

	@Test
	void 발송_실패는_502_이고_행을_남기지_않는다() throws Exception {
		mail.failNext = true;
		mockMvc.perform(post("/v1/auth/email-verification/send").with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"email\":\"verify-fail@example.com\"}"))
				.andExpect(status().isBadGateway())
				.andExpect(jsonPath("$.error.code").value("EMAIL_SEND_FAILED"));
		Long count = jdbcClient.sql(
						"SELECT count(*) FROM app.email_verifications WHERE email = 'verify-fail@example.com'")
				.query(Long.class).single();
		assertThat(count).isZero();
	}

	@Test
	void 재발송하면_이전_코드는_무효() throws Exception {
		// 컨트롤러 쿨다운(분당 1회)을 우회해 서비스 계약만 검증 — 두 번째 발송은 리포지토리 upsert 경로
		send("verify-resend@example.com", 204);
		String first = mail.lastCode();
		jdbcClient.sql("""
				UPDATE app.email_verifications SET code_hash = 'replaced-by-resend'
				WHERE email = 'verify-resend@example.com'""").update();
		confirm("verify-resend@example.com", first, 400);
	}
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :was:test --tests 'com.celfit.was.EmailVerificationIntegrationTest'`
Expected: 컴파일 실패(`V1AuthTestSteps.signUp` 3-인자 시그니처는 Task 6에서 생김) — **이 시점에는 `기가입_이메일_발송은_409` 테스트를 `@org.junit.jupiter.api.Disabled("Task 6에서 활성화")`로 잠시 막고** 나머지가 404/컴파일 오류로 실패하는 것을 확인

- [ ] **Step 3: 서비스 구현**

```java
package com.celfit.was.v1.account;

import com.celfit.was.mail.MailSender;
import com.celfit.was.v1.common.V1ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import org.springframework.stereotype.Service;

/**
 * 이메일 소유권 인증(설계 2026-07-18) — 6자리 코드 발송·확인·가입 게이트.
 * 주 방어선은 TTL 10분 + 오입력 5회 + 레이트리밋(컨트롤러). confirm 실패는 사유 비구분
 * 400 INVALID_CODE(부재·만료·시도 초과·불일치 동일 응답 — 열거 방지).
 */
@Service
public class EmailVerificationService {

	static final Duration CODE_TTL = Duration.ofMinutes(10);
	static final Duration VERIFIED_TTL = Duration.ofMinutes(30);
	static final int MAX_ATTEMPTS = 5;

	private final EmailVerificationRepository repository;
	private final MailSender mailSender;
	private final Clock clock;
	private final SecureRandom random = new SecureRandom();

	public EmailVerificationService(EmailVerificationRepository repository, MailSender mailSender, Clock clock) {
		this.repository = repository;
		this.mailSender = mailSender;
		this.clock = clock;
	}

	/** 코드 생성→발송→저장. 발송 성공 후에만 저장(실패했는데 코드가 유효해지는 상태 방지). MailSendException은 컨트롤러가 502로 변환. */
	public void sendCode(String email) {
		String code = "%06d".formatted(random.nextInt(1_000_000));
		mailSender.send(email, "[hypenow] 이메일 인증 코드",
				"인증 코드: %s%n%n10분 안에 가입 화면에 입력해 주세요.".formatted(code));
		repository.upsert(email, sha256(code), clock.instant().plus(CODE_TTL));
	}

	/** 판정 순서: 행 존재 → 시도 한도 → 만료 → 해시 일치. 해시 불일치만 attempts를 올린다(만료·부재는 카운트 무의미). */
	public void confirm(String email, String code) {
		EmailVerificationRepository.Verification row = repository.find(email)
				.orElseThrow(EmailVerificationService::invalidCode);
		if (row.attempts() >= MAX_ATTEMPTS || clock.instant().isAfter(row.codeExpiresAt())) {
			throw invalidCode();
		}
		if (code == null || !row.codeHash().equals(sha256(code.trim()))) {
			repository.incrementAttempts(email);
			throw invalidCode();
		}
		repository.markVerified(email, clock.instant());
	}

	/** 가입 직전 게이트 — verified_at 존재 + 30분 이내. 아니면 403(재발송→재확인으로 복구). */
	public void requireVerified(String email) {
		boolean verified = repository.find(email)
				.map(EmailVerificationRepository.Verification::verifiedAt)
				.map(at -> at != null && !clock.instant().isAfter(at.plus(VERIFIED_TTL)))
				.orElse(false);
		if (!verified) {
			throw V1ApiException.forbidden("EMAIL_NOT_VERIFIED", "이메일 인증을 먼저 완료해 주세요.");
		}
	}

	/** 가입 성공 직후 1회 소비 — 잔존해도 verified 30분 만료로 무해(원자성 불요). */
	public void consume(String email) {
		repository.delete(email);
	}

	private static V1ApiException invalidCode() {
		return V1ApiException.badRequest("INVALID_CODE", "인증 코드를 확인해 주세요.");
	}

	static String sha256(String value) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 미지원 JVM", e);
		}
	}
}
```

- [ ] **Step 4: 컨트롤러 구현**

```java
package com.celfit.was.v1.account;

import com.celfit.was.auth.UserRepository;
import com.celfit.was.mail.MailSendException;
import com.celfit.was.v1.common.V1ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * /v1/auth/email-verification 발송·확인(설계 2026-07-18) — 가입 전 이메일 소유권 인증.
 * 익명 표면(화이트리스트 /v1/auth/**)이라 레이트리밋이 1차 방어. 이메일은 저장 규칙과 동일 lower 정규화.
 */
@RestController
public class V1EmailVerificationController {

	public record SendRequest(String email) {
	}

	public record ConfirmRequest(String email, String code) {
	}

	private final EmailVerificationService emailVerificationService;
	private final SignupValidator signupValidator;
	private final RateLimiter rateLimiter;
	private final UserRepository userRepository;

	public V1EmailVerificationController(EmailVerificationService emailVerificationService,
			SignupValidator signupValidator, RateLimiter rateLimiter, UserRepository userRepository) {
		this.emailVerificationService = emailVerificationService;
		this.signupValidator = signupValidator;
		this.rateLimiter = rateLimiter;
		this.userRepository = userRepository;
	}

	@PostMapping("/v1/auth/email-verification/send")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void send(@RequestBody SendRequest request, HttpServletRequest httpRequest) {
		String email = request.email() == null ? "" : UserRepository.normalizeEmail(request.email());
		// 이메일당 분당 1회(재발송 쿨다운) + IP당 분당 5회 — 익명 발송 남용 차단
		if (!rateLimiter.tryAcquire("email-verify-send:" + email, 1)
				|| !rateLimiter.tryAcquire("email-verify-send-ip:" + httpRequest.getRemoteAddr(), 5)) {
			throw V1ApiException.rateLimited();
		}
		signupValidator.requireEmail(request.email());
		if (userRepository.findByEmail(email).isPresent()) {
			throw V1ApiException.conflict("EMAIL_ALREADY_EXISTS", "이미 가입된 이메일이에요. 로그인해 주세요.");
		}
		try {
			emailVerificationService.sendCode(email);
		} catch (MailSendException e) {
			throw V1ApiException.badGateway("EMAIL_SEND_FAILED", "인증 메일 발송에 실패했어요. 잠시 후 다시 시도해 주세요.");
		}
	}

	@PostMapping("/v1/auth/email-verification/confirm")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void confirm(@RequestBody ConfirmRequest request, HttpServletRequest httpRequest) {
		if (!rateLimiter.tryAcquire("email-verify-confirm:" + httpRequest.getRemoteAddr(), 10)) {
			throw V1ApiException.rateLimited();
		}
		signupValidator.requireEmail(request.email());
		emailVerificationService.confirm(UserRepository.normalizeEmail(request.email()), request.code());
	}
}
```

- [ ] **Step 5: 통과 확인** (`기가입_이메일_발송은_409`는 아직 @Disabled)

Run: `./gradlew :was:test --tests 'com.celfit.was.EmailVerificationIntegrationTest'`
Expected: PASS (Disabled 1건 제외)

- [ ] **Step 6: 커밋**

```bash
git add was/src/main/java/com/celfit/was/v1/account/EmailVerificationService.java \
        was/src/main/java/com/celfit/was/v1/account/V1EmailVerificationController.java \
        was/src/test/java/com/celfit/was/EmailVerificationIntegrationTest.java
git commit -m "feat(was): 이메일 인증 send·confirm 엔드포인트 — 6자리 코드·TTL 10분·오입력 5회"
```

---

### Task 6: signup 가입 전 강제 배선 + 기존 테스트 일괄 갱신

**Files:**
- Modify: `was/src/main/java/com/celfit/was/v1/account/V1AuthController.java` (signup)
- Modify: `was/src/test/java/com/celfit/was/V1AuthTestSteps.java` (시드 헬퍼 + signUp 시그니처)
- Modify: `was/src/test/java/com/celfit/was/EmailVerificationIntegrationTest.java` (@Disabled 해제 + signup 게이트 테스트 추가)
- Modify: 가입을 수행하는 기존 테스트 전부 (Step 4에서 grep으로 확정)

- [ ] **Step 1: V1AuthTestSteps에 시드 헬퍼 추가 + signUp 시그니처 변경**

```java
	/**
	 * 이메일 인증 우회 시드 — 가입 전 강제(설계 2026-07-18) 이후 signup 전에 필요.
	 * send/confirm 왕복 없이 verified 행을 직접 심는다(코드 해시는 무관 값).
	 */
	public static void markEmailVerified(JdbcClient jdbcClient, String email) {
		jdbcClient.sql("""
				INSERT INTO app.email_verifications (email, code_hash, code_expires_at, verified_at)
				VALUES (lower(trim(:email)), 'seeded', now(), now())
				ON CONFLICT (email) DO UPDATE SET verified_at = now()""")
				.param("email", email)
				.update();
	}

	/** 가입(자동 로그인) 후 hypenow-session 쿠키 반환 — 이메일 인증 시드 포함. */
	public static Cookie signUp(MockMvc mockMvc, JdbcClient jdbcClient, String email) throws Exception {
		markEmailVerified(jdbcClient, email);
		MvcResult result = mockMvc.perform(post("/v1/auth/signup").with(csrf())
						.contentType(MediaType.APPLICATION_JSON).content(signupBody(email)))
				.andExpect(status().isCreated())
				.andReturn();
		Cookie session = result.getResponse().getCookie("hypenow-session");
		assertThat(session).isNotNull();
		return session;
	}
```

(기존 2-인자 `signUp(MockMvc, String)`은 삭제 — 호출부는 Step 4에서 일괄 수정.)

- [ ] **Step 2: 실패하는 signup 게이트 테스트 추가** — `EmailVerificationIntegrationTest`에 추가하고 `기가입_이메일_발송은_409`의 `@Disabled` 해제:

```java
	@Test
	void 미인증_이메일_가입은_403_EMAIL_NOT_VERIFIED() throws Exception {
		mockMvc.perform(post("/v1/auth/signup").with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content(V1AuthTestSteps.signupBody("verify-gate@example.com")))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error.code").value("EMAIL_NOT_VERIFIED"));
	}

	@Test
	void 인증_30분_초과_가입은_403() throws Exception {
		V1AuthTestSteps.markEmailVerified(jdbcClient, "verify-stale@example.com");
		jdbcClient.sql("""
				UPDATE app.email_verifications SET verified_at = now() - interval '31 minutes'
				WHERE email = 'verify-stale@example.com'""").update();
		mockMvc.perform(post("/v1/auth/signup").with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content(V1AuthTestSteps.signupBody("verify-stale@example.com")))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error.code").value("EMAIL_NOT_VERIFIED"));
	}

	@Test
	void 가입_성공시_인증_행이_소비된다() throws Exception {
		V1AuthTestSteps.signUp(mockMvc, jdbcClient, "verify-consume@example.com");
		Long count = jdbcClient.sql(
						"SELECT count(*) FROM app.email_verifications WHERE email = 'verify-consume@example.com'")
				.query(Long.class).single();
		assertThat(count).isZero();
	}

	@Test
	void 가입_코드_검증이_이메일_인증보다_먼저다() throws Exception {
		// 미인증 + 잘못된 가입 코드 → INVALID_SIGNUP_CODE(스펙 §4 순서: 가입 코드가 선행)
		String body = V1AuthTestSteps.signupBody("verify-order@example.com")
				.replace(V1AuthTestSteps.SIGNUP_CODE, "WRONG-CODE");
		mockMvc.perform(post("/v1/auth/signup").with(csrf())
						.contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error.code").value("INVALID_SIGNUP_CODE"));
	}
```

- [ ] **Step 3: V1AuthController.signup에 게이트·소비 배선**

생성자에 `EmailVerificationService emailVerificationService` 필드·파라미터 추가(기존 관용구대로 대입), `signup()`을 다음과 같이 수정:

```java
		verifySignupCode(request.signupCode());
		signupValidator.validate(request);
		// 이메일 소유권 인증(설계 2026-07-18) — 가입 전 강제. verified 30분 이내가 아니면 403
		String email = UserRepository.normalizeEmail(request.email());
		emailVerificationService.requireVerified(email);

		UserProfile profile;
		try {
			profile = userRepository.insertProfile(request.toNewUser(), passwordEncoder.encode(request.password()));
		} catch (DuplicateKeyException e) {
			throw V1ApiException.conflict("EMAIL_ALREADY_EXISTS", "이미 가입된 이메일이에요. 로그인해 주세요.");
		}
		emailVerificationService.consume(email);
```

(`import com.celfit.was.v1.common.V1ApiException;`는 기존에 있음. `UserRepository`는 이미 import됨.)

- [ ] **Step 4: 가입을 수행하는 기존 테스트 전부 갱신**

먼저 호출부를 확정한다:

```bash
grep -rln "V1AuthTestSteps.signUp\|/v1/auth/signup" was/src/test/java
```

- `V1AuthTestSteps.signUp(mockMvc, email)` 호출부(예: `SavedApiIntegrationTest`) → `V1AuthTestSteps.signUp(mockMvc, jdbcClient, email)`로 변경(해당 테스트에 `@Autowired JdbcClient jdbcClient`가 없으면 추가).
- `/v1/auth/signup`을 직접 POST 하는 통합 테스트(예: `LoginWallIntegrationTest`·`CsrfCookieFlowIntegrationTest`·`SessionPersistenceIntegrationTest` 등 grep 결과 전부) → 가입 POST 직전에 `V1AuthTestSteps.markEmailVerified(jdbcClient, "<그 테스트의 이메일>");` 한 줄 추가.
- `was/src/test/java/com/celfit/was/v1/account/V1AuthControllerTest.java`(슬라이스 테스트)가 있으면: `@MockitoBean EmailVerificationService emailVerificationService;` 추가(기존 목 빈 나열과 같은 방식) — `requireVerified`는 기본 no-op 목이라 기존 테스트는 그대로 통과한다.

- [ ] **Step 5: 전체 테스트로 회귀 확인**

Run: `./gradlew :was:test`
Expected: PASS (전 통합 테스트 포함)

- [ ] **Step 6: 커밋**

```bash
git add -A was/src
git commit -m "feat(was): 가입 전 이메일 인증 강제 — signup 403 EMAIL_NOT_VERIFIED 게이트 + 성공 시 1회 소비"
```

---

### Task 7: 문서 반영 + 전체 검증 + PR

**Files:**
- Modify: `ARCHITECTURE.md` (§3 app 스키마 테이블 목록, §5 G 행, §7 결정 기록)
- Modify: `docs/superpowers/specs/2026-07-15-hypenow-api-spec-alignment-design.md` (상태 헤더에만 6.17 해소 링크 추가 — 본문 불변)
- Modify: `docs/superpowers/specs/2026-07-18-email-verification-design.md` (상태 헤더를 ✅ 구현됨으로)

- [ ] **Step 1: ARCHITECTURE.md 갱신**

- §3 `app` 스키마 테이블 나열부에 `email_verifications`(V7 — 이메일 인증 코드·verified 상태, 가입 시 소비) 추가.
- §5 G 행의 "이메일 **소유권 인증**(스펙 6.17)은 [TBD] 미구현…" 문구를 "+ 이메일 소유권 인증(6.17 — V7 `email_verifications`·send/confirm·가입 전 강제, [specs/2026-07-18-email-verification-design.md](docs/superpowers/specs/2026-07-18-email-verification-design.md))"으로 교체.
  (주의: PR #38이 이 행을 먼저 고쳤다 — 충돌 시 #38 머지 후 리베이스.)
- §7 맨 위에 결정 기록 1행 추가:

```markdown
| 2026-07-19 | **이메일 소유권 인증 구현(6.17 [TBD] 해소)** — 가입 전 강제(스텝5), 6자리 코드(TTL 10분·오입력 5회), Resend HTTPS 발송(키 미설정 시 로깅 폴백), 서버 상태 방식(V7 email_verifications, verified 30분·가입 시 1회 소비). signup 검증 순서에 403 EMAIL_NOT_VERIFIED 삽입(가입 코드 → 필드 → 이메일 인증 → 중복). 운영 개통은 Resend 도메인 인증 + RESEND_API_KEY 등록 필요 — 프론트 배선(REST 전환) 전까지 운영 signup은 인증 선행 없이는 403 | [specs/2026-07-18-email-verification-design.md](docs/superpowers/specs/2026-07-18-email-verification-design.md) |
```

- [ ] **Step 2: 스펙 상태 헤더 2건 갱신**

- `2026-07-18-email-verification-design.md` 헤더: `> 상태: 🟢 활성` → `> 상태: 🟢 활성 · ✅ 구현됨(was) — 프론트 배선은 REST 전환(celfit-front PR #18 계속) 대기`
- `2026-07-15-hypenow-api-spec-alignment-design.md` 헤더(`> 상태: 🟢 활성` 줄) 아래에 한 줄 추가:
  `> 6.17 이메일 인증 [TBD]는 [2026-07-18-email-verification-design.md](2026-07-18-email-verification-design.md)로 해소(07-19 구현)`

- [ ] **Step 3: 전체 테스트**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL (crawler·analytics·was 전 모듈)

- [ ] **Step 4: 커밋 + 푸시 + PR**

```bash
git add ARCHITECTURE.md docs/superpowers/specs/
git commit -m "docs: 이메일 인증 구현 반영 — §3·§5 G·§7 갱신, 6.17 [TBD] 해소 링크"
git push -u origin feat/email-verification
gh pr create --base develop --title "feat(was): 이메일 소유권 인증 — 가입 전 강제·6자리 코드·Resend (스펙 6.17 해소)" --body "<스펙·검증 결과 요약>"
```

PR 본문에 반드시 포함: 스펙 링크, `./gradlew test` 결과, **운영 개통 체크리스트**(Resend 도메인 인증 DNS·`RESEND_API_KEY` 서버 .env + `deploy/compose.yaml` 환경변수·프론트 REST 전환 전 배포 시 운영 signup 403 주의 — 스펙 §11).

---

## 자체 검토 노트 (플래너 → 실행자)

- **스펙 §4 send 검증 순서**: 레이트리밋 → 형식 → 기가입 409 → 발송. 형식 오류도 레이트리밋 카운트에 포함되는데(키가 정규화 문자열) 이는 의도(쓰레기 입력도 남용) — 테스트가 이 순서를 강제하지는 않음.
- **RateLimiter 이메일 키 상한 1**: `같은_이메일_분당_2회_발송은_429` 테스트가 분 경계에 걸치면 이론상 플레이크 — 실패 시 해당 테스트만 재실행으로 판단(기존 RateLimiterTest는 고정 Clock이라 무관).
- **V1AuthControllerTest가 존재하지 않거나 통합형이면** Task 6 Step 4의 슬라이스 지침은 건너뛴다(grep 결과 기준으로 판단).
- **Flyway V7 번호**: `db/migration/app`의 최신은 V6 — V7 충돌 없음(§4-5 번호 예약 규칙 확인 완료).
- 커밋 메시지·주석은 한국어, 인덴트는 탭(기존 파일 관용구).
