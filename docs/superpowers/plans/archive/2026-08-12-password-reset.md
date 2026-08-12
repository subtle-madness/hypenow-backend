# 비밀번호 재설정 API 구현 계획

> 상태: ✅ 실행 완료 (2026-08-12)
>
> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 프론트 요청(2026-08-12)의 비밀번호 재설정 엔드포인트 3종(`/v1/auth/password-reset/send`·`/confirm`·`/password-reset`)을 was에 구현한다.

**Architecture:** 07-29에 철거된 was mail 패키지(Resend HTTPS 발송 + 로깅 폴백)를 커밋 `cc14c717^`에서 그대로 복원하고, 옛 이메일 인증(6.17) 구현을 재설정 플로우로 개작한다. 이메일당 1행(`app.password_resets`)이 코드 단계→토큰 단계를 순차로 담고, confirm 성공 시 코드를 소모(NULL)하며 SHA-256 해시로만 저장한 1회용 토큰(prt_ 접두, TTL 10분)을 발급, reset 성공 시 행을 삭제하고 유저 세션 전부를 무효화한다. 시간당 한도는 RateLimiter에 윈도우 길이 파라미터를 추가해 처리한다.

**Tech Stack:** Java 21, Spring Boot 4.1, JdbcClient, Spring Session JDBC, Flyway(UTC 타임스탬프 채번), Testcontainers 통합 테스트, Resend HTTPS API.

**전제:** 작업은 `.worktrees/password-reset` 워크트리의 `feat/password-reset` 브랜치에서 한다(superpowers:using-git-worktrees). 테스트 전 `export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock` 필수(CLAUDE.md 함정 참조). **PR은 열지 않는다 — push·보고까지만 하고 사용자 승인을 기다린다.**

**계약 요약(프론트 요청서 그대로):**

| 엔드포인트 | 성공 | 에러 |
|---|---|---|
| `POST /v1/auth/password-reset/send` {email} | 204 | 400 VALIDATION_FAILED · 404 USER_NOT_FOUND · 429 RATE_LIMITED · 502 EMAIL_SEND_FAILED(요청서에 없으나 6.17 관용구 유지 — 프론트에 회신) |
| `POST /v1/auth/password-reset/confirm` {email, code} | 200 `{resetToken, expiresIn}` envelope | 400 VALIDATION_FAILED · 400 INVALID_VERIFICATION_CODE · 429 RATE_LIMITED |
| `POST /v1/auth/password-reset` {resetToken, newPassword} | 204 (Set-Cookie 없음) | 400 VALIDATION_FAILED · 400 INVALID_RESET_TOKEN · 429 RATE_LIMITED |

정책: 코드 6자리·TTL 5분·오입력 5회·재발송 쿨다운 60초·이메일당 시간당 5회·IP당 시간당 20회. 토큰 TTL 10분(expiresIn 600)·1회용. 비밀번호 정책 검증은 프론트 단일 관할(서버는 빈 값만 차단 — SignupValidator.validatePassword 그대로). reset 성공 시 해당 유저 세션 전부 무효화(SessionService.deleteAll), 자동 로그인 없음.

시큐리티: `/v1/auth/**`는 이미 permitAll(SecurityConfig:162) — 추가 설정 불요.

---

### Task 1: mail 패키지 복원 (커밋 cc14c717^에서 그대로)

**Files:**
- Create: `was/src/main/java/com/celfit/was/mail/{MailSender,ResendMailSender,LoggingMailSender,MailConfig,MailSendException}.java` (히스토리에서 복원)
- Test: `was/src/test/java/com/celfit/was/mail/ResendMailSenderTest.java` (히스토리에서 복원)
- Modify: `was/src/main/resources/application.yml` (was 블록에 mail 설정 복원)

- [ ] **Step 1: 철거 직전 판을 그대로 꺼낸다** (검증된 코드의 문자 그대로 복원 — 손 타이핑 금지)

```bash
cd /Users/woomin/Project/hypenow-backend/.worktrees/password-reset
mkdir -p was/src/main/java/com/celfit/was/mail was/src/test/java/com/celfit/was/mail
for f in MailSender ResendMailSender LoggingMailSender MailConfig MailSendException; do
  git show cc14c717^:was/src/main/java/com/celfit/was/mail/$f.java > was/src/main/java/com/celfit/was/mail/$f.java
done
git show cc14c717^:was/src/test/java/com/celfit/was/mail/ResendMailSenderTest.java > was/src/test/java/com/celfit/was/mail/ResendMailSenderTest.java
```

- [ ] **Step 2: application.yml의 `was:` 블록(58행 부근, cors 위)에 mail 설정 삽입**

```yaml
was:
  mail:
    resend-api-key: ${RESEND_API_KEY:}    # 빈 값 → LoggingMailSender(발송 대신 로그 — 로컬·테스트)
    from: "hypenow <no-reply@hypenow.io>" # Resend 도메인 인증(hypenow.io SPF/DKIM) 필요 — 운영 체크리스트
  cors:
    ...기존 유지...
```

- [ ] **Step 3: 복원분 테스트 실행**

Run: `./gradlew :was:test --tests "com.celfit.was.mail.ResendMailSenderTest"`
Expected: PASS (MailConfig의 `MailSendException` 경로까지 옛 테스트가 커버)

- [ ] **Step 4: MailConfig 주석 1곳 손질** — "동기 가입 경로에서 호출되므로" → "동기 재설정 발송 경로에서 호출되므로" (가입 인증은 철거됐고 이번 용도는 비밀번호 재설정)

- [ ] **Step 5: Commit**

```bash
git add was/src/main/java/com/celfit/was/mail was/src/test/java/com/celfit/was/mail was/src/main/resources/application.yml
git commit -m "feat(was): mail 발송 스택 복원(cc14c717 철거분) — 비밀번호 재설정 발송용

Resend HTTPS 포트·로깅 폴백·타임아웃(5s/10s) 그대로. 철거 사유였던 '임의 주소
발송 남용 표면'은 재설정이 가입된 이메일에만 발송(404)하므로 성립하지 않는다."
```

---

### Task 2: RateLimiter 윈도우 길이 파라미터 (시간당 한도 지원)

**Files:**
- Modify: `was/src/main/java/com/celfit/was/v1/account/RateLimiter.java`
- Test: `was/src/test/java/com/celfit/was/v1/account/RateLimiterTest.java`

- [ ] **Step 1: 실패하는 테스트 2개 추가** (기존 SteppingClock 재사용 — 시작 시각 2026-07-15T00:00:00Z는 60분 경계라 윈도우 계산이 결정적)

```java
	@Test
	void 윈도우_분_수를_넘기면_카운터가_리셋된다() {
		SteppingClock clock = new SteppingClock();
		RateLimiter limiter = new RateLimiter(clock, 10);

		assertThat(limiter.tryAcquire("h", 1, 60)).isTrue();
		assertThat(limiter.tryAcquire("h", 1, 60)).isFalse();

		clock.advance(Duration.ofMinutes(59));
		assertThat(limiter.tryAcquire("h", 1, 60)).isFalse(); // 같은 60분 윈도우

		clock.advance(Duration.ofMinutes(1));
		assertThat(limiter.tryAcquire("h", 1, 60)).isTrue(); // 다음 윈도우
	}

	@Test
	void 시간_윈도우는_분_스윕에_청소되지_않는다() {
		SteppingClock clock = new SteppingClock();
		RateLimiter limiter = new RateLimiter(clock, 10);

		assertThat(limiter.tryAcquire("h", 1, 60)).isTrue();
		clock.advance(Duration.ofMinutes(1));
		limiter.tryAcquire("m", 1); // 분이 바뀐 첫 호출 — 스윕 트리거

		// 60분 윈도우가 분 스윕에 지워졌다면 카운터 리셋으로 true가 됐을 것
		assertThat(limiter.tryAcquire("h", 1, 60)).isFalse();
	}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :was:test --tests "com.celfit.was.v1.account.RateLimiterTest"`
Expected: FAIL — `tryAcquire(String,int,int)` 미정의 컴파일 에러

- [ ] **Step 3: 구현** — Window에 윈도우 길이를 담고, 스윕은 "윈도우 종료 시각이 지난 것"만 제거(1분 윈도우의 기존 `epochMinute < minute` 판정과 동치라 기존 의미 불변)

```java
	private record Window(long windowStart, int windowMinutes, AtomicInteger count) {
	}
```

```java
	/** 허용되면 true. 분이 바뀌면 카운터 리셋(고정 윈도우). 기본 상한(was.rate-limit.per-minute). */
	public boolean tryAcquire(String key) {
		return tryAcquire(key, perMinute, 1);
	}

	/** 경로별 상한이 다른 경우(재설정 발송 분당 1회 등) — 윈도우 구조는 공유, 상한만 오버라이드. */
	public boolean tryAcquire(String key, int limit) {
		return tryAcquire(key, limit, 1);
	}

	/**
	 * 시간 단위 상한(재설정 발송 시간당 5회 등) — windowMinutes 길이의 고정 윈도우.
	 * 긴 윈도우는 키가 최대 windowMinutes분 잔존하므로 공격자 제어 키(이메일)는
	 * 반드시 IP 상한과 함께 건다(맵 성장 상한).
	 */
	public boolean tryAcquire(String key, int limit, int windowMinutes) {
		long minute = clock.instant().getEpochSecond() / 60;
		sweepIfMinuteChanged(minute);
		long windowStart = (minute / windowMinutes) * windowMinutes;
		Window w = windows.compute(key, (k, old) ->
				(old == null || old.windowStart() != windowStart || old.windowMinutes() != windowMinutes)
						? new Window(windowStart, windowMinutes, new AtomicInteger()) : old);
		return w.count().incrementAndGet() <= limit;
	}
```

sweep의 removeIf 한 줄 교체(가드·단조 증가 로직은 그대로):

```java
			windows.entrySet().removeIf(e ->
					e.getValue().windowStart() + e.getValue().windowMinutes() <= minute);
```

- [ ] **Step 4: 통과 확인** (기존 6개 테스트 포함 전부)

Run: `./gradlew :was:test --tests "com.celfit.was.v1.account.RateLimiterTest"`
Expected: PASS 8개

- [ ] **Step 5: Commit**

```bash
git add was/src/main/java/com/celfit/was/v1/account/RateLimiter.java was/src/test/java/com/celfit/was/v1/account/RateLimiterTest.java
git commit -m "feat(was): RateLimiter 윈도우 길이 파라미터 — 시간당 한도 지원(재설정 발송용)"
```

---

### Task 3: password_resets 테이블 + 리포지토리

**Files:**
- Create: `was/src/main/resources/db/migration/app/V20260812120000__password_resets.sql`
- Create: `was/src/main/java/com/celfit/was/v1/account/PasswordResetRepository.java`
- Test: `was/src/test/java/com/celfit/was/v1/account/PasswordResetRepositoryTest.java`

- [ ] **Step 1: 채번 충돌 사전 확인** (flyway-version-collision-check 수칙)

```bash
ls was/src/main/resources/db/migration/app/ | grep 20260812
gh pr list --state open --json headRefName -q '.[].headRefName'
```
Expected: 20260812 채번 없음. 열린 PR 브랜치에 app 마이그레이션 추가분이 있으면 번호 확인.

- [ ] **Step 2: 마이그레이션 작성** — `V20260812120000__password_resets.sql`

```sql
-- 비밀번호 재설정(프론트 요청 2026-08-12) — 이메일당 1행이 2단계 상태를 순차로 담는다:
-- 코드 단계(code_hash·code_expires_at·attempts) → confirm 성공 시 코드 소모(code_hash NULL)
-- + 토큰 발급(token_hash·token_expires_at) → reset 성공 시 행 삭제(토큰 1회용).
-- 재발송 upsert는 코드 교체 + attempts·토큰 리셋(마지막 발송만 유효 — email_verifications V7 관용구).
CREATE TABLE app.password_resets (
    email            text PRIMARY KEY,
    code_hash        text,
    code_expires_at  timestamptz NOT NULL,
    attempts         int NOT NULL DEFAULT 0,
    token_hash       text,
    token_expires_at timestamptz,
    created_at       timestamptz NOT NULL DEFAULT now()
);

-- reset(요청 3)은 토큰만 들고 온다 — 조회가 token_hash 기준이라 유니크 인덱스(NULL 다중 허용)
CREATE UNIQUE INDEX password_resets_token_hash_key ON app.password_resets (token_hash);
```

- [ ] **Step 3: 실패하는 리포지토리 테스트 작성** — `PasswordResetRepositoryTest.java`

```java
package com.celfit.was.v1.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.celfit.was.IntegrationTest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

class PasswordResetRepositoryTest extends IntegrationTest {

	@Autowired
	PasswordResetRepository repository;

	@Autowired
	JdbcClient jdbcClient;

	static final String EMAIL = "reset-repo@example.com";

	@BeforeEach
	void clean() {
		jdbcClient.sql("DELETE FROM app.password_resets").update();
	}

	@Test
	void upsert_후_find로_행을_읽는다() {
		Instant expiresAt = Instant.now().plusSeconds(300);
		repository.upsert(EMAIL, "code-hash-1", expiresAt);

		var row = repository.find(EMAIL).orElseThrow();
		assertThat(row.email()).isEqualTo(EMAIL);
		assertThat(row.codeHash()).isEqualTo("code-hash-1");
		assertThat(row.attempts()).isZero();
		assertThat(row.tokenHash()).isNull();
		assertThat(row.codeExpiresAt().toInstant()).isCloseTo(expiresAt, within(1, ChronoUnit.SECONDS));
	}

	@Test
	void 재발송_upsert는_코드를_교체하고_attempts와_토큰을_리셋한다() {
		repository.upsert(EMAIL, "code-hash-1", Instant.now().plusSeconds(300));
		repository.incrementAttempts(EMAIL);
		repository.consumeCodeAndIssueToken(EMAIL, "token-hash-1", Instant.now().plusSeconds(600));

		repository.upsert(EMAIL, "code-hash-2", Instant.now().plusSeconds(300));

		var row = repository.find(EMAIL).orElseThrow();
		assertThat(row.codeHash()).isEqualTo("code-hash-2");
		assertThat(row.attempts()).isZero();
		assertThat(row.tokenHash()).isNull();
		assertThat(row.tokenExpiresAt()).isNull();
	}

	@Test
	void 코드_소모와_토큰_발급_후_토큰으로_행을_찾는다() {
		repository.upsert(EMAIL, "code-hash-1", Instant.now().plusSeconds(300));
		Instant tokenExpiresAt = Instant.now().plusSeconds(600);

		repository.consumeCodeAndIssueToken(EMAIL, "token-hash-1", tokenExpiresAt);

		var row = repository.findByTokenHash("token-hash-1").orElseThrow();
		assertThat(row.email()).isEqualTo(EMAIL);
		assertThat(row.codeHash()).isNull(); // 코드 소모 — 같은 코드 재confirm 불가
		assertThat(row.tokenExpiresAt().toInstant()).isCloseTo(tokenExpiresAt, within(1, ChronoUnit.SECONDS));
		assertThat(repository.findByTokenHash("없는-해시")).isEmpty();
	}

	@Test
	void delete는_행을_지운다() {
		repository.upsert(EMAIL, "code-hash-1", Instant.now().plusSeconds(300));
		repository.delete(EMAIL);
		assertThat(repository.find(EMAIL)).isEmpty();
	}
}
```

- [ ] **Step 4: 실패 확인**

Run: `./gradlew :was:test --tests "com.celfit.was.v1.account.PasswordResetRepositoryTest"`
Expected: FAIL — PasswordResetRepository 미정의 컴파일 에러

- [ ] **Step 5: 리포지토리 구현** — `PasswordResetRepository.java` (EmailVerificationRepository 관용구 이식)

```java
package com.celfit.was.v1.account;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * app.password_resets 접근 — 이메일당 1행. 재발송 upsert → confirm에서 코드 소모·토큰 기록 →
 * reset에서 행 삭제(토큰 1회 소비). 코드·토큰은 SHA-256 해시로만 저장(원문 무저장).
 */
@Repository
public class PasswordResetRepository {

	public record ResetChallenge(String email, String codeHash, OffsetDateTime codeExpiresAt,
			int attempts, String tokenHash, OffsetDateTime tokenExpiresAt) {
	}

	private final JdbcClient jdbcClient;

	public PasswordResetRepository(JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	/** 발송 성공 후에만 호출 — 기존 행이 있으면 코드 교체 + attempts·토큰 리셋(마지막 발송만 유효). */
	public void upsert(String email, String codeHash, Instant codeExpiresAt) {
		jdbcClient.sql("""
				INSERT INTO app.password_resets (email, code_hash, code_expires_at)
				VALUES (:email, :codeHash, :codeExpiresAt)
				ON CONFLICT (email) DO UPDATE
				SET code_hash = EXCLUDED.code_hash, code_expires_at = EXCLUDED.code_expires_at,
				    attempts = 0, token_hash = NULL, token_expires_at = NULL, created_at = now()""")
				.param("email", email)
				.param("codeHash", codeHash)
				.param("codeExpiresAt", OffsetDateTime.ofInstant(codeExpiresAt, ZoneOffset.UTC))
				.update();
	}

	public Optional<ResetChallenge> find(String email) {
		return jdbcClient.sql("""
				SELECT email, code_hash, code_expires_at, attempts, token_hash, token_expires_at
				FROM app.password_resets WHERE email = :email""")
				.param("email", email)
				.query(ResetChallenge.class)
				.optional();
	}

	public Optional<ResetChallenge> findByTokenHash(String tokenHash) {
		return jdbcClient.sql("""
				SELECT email, code_hash, code_expires_at, attempts, token_hash, token_expires_at
				FROM app.password_resets WHERE token_hash = :tokenHash""")
				.param("tokenHash", tokenHash)
				.query(ResetChallenge.class)
				.optional();
	}

	/** 해시 불일치 오입력 카운트 — 만료·부재는 세지 않는다(서비스 판정 순서 참조). */
	public void incrementAttempts(String email) {
		jdbcClient.sql("UPDATE app.password_resets SET attempts = attempts + 1 WHERE email = :email")
				.param("email", email)
				.update();
	}

	/** confirm 성공 — 코드를 소모(NULL)하고 토큰 해시를 기록한다(같은 코드 재검증 차단). */
	public void consumeCodeAndIssueToken(String email, String tokenHash, Instant tokenExpiresAt) {
		jdbcClient.sql("""
				UPDATE app.password_resets
				SET code_hash = NULL, token_hash = :tokenHash, token_expires_at = :tokenExpiresAt
				WHERE email = :email""")
				.param("tokenHash", tokenHash)
				.param("tokenExpiresAt", OffsetDateTime.ofInstant(tokenExpiresAt, ZoneOffset.UTC))
				.param("email", email)
				.update();
	}

	/** reset 성공(토큰 1회 소비) 또는 만료 행 청소. */
	public void delete(String email) {
		jdbcClient.sql("DELETE FROM app.password_resets WHERE email = :email")
				.param("email", email)
				.update();
	}
}
```

- [ ] **Step 6: 통과 확인**

Run: `./gradlew :was:test --tests "com.celfit.was.v1.account.PasswordResetRepositoryTest"`
Expected: PASS 4개

- [ ] **Step 7: Commit**

```bash
git add was/src/main/resources/db/migration/app/V20260812120000__password_resets.sql was/src/main/java/com/celfit/was/v1/account/PasswordResetRepository.java was/src/test/java/com/celfit/was/v1/account/PasswordResetRepositoryTest.java
git commit -m "feat(was): password_resets 테이블·리포지토리 — 코드→토큰 2단계 상태를 이메일당 1행에"
```

---

### Task 4: 서비스 + 컨트롤러 + 통합 테스트

**Files:**
- Create: `was/src/main/java/com/celfit/was/v1/account/PasswordResetService.java`
- Create: `was/src/main/java/com/celfit/was/v1/account/V1PasswordResetController.java`
- Modify: `was/src/main/java/com/celfit/was/v1/common/V1ApiException.java` (notFound 코드 지정 오버로드)
- Test: `was/src/test/java/com/celfit/was/PasswordResetIntegrationTest.java`

- [ ] **Step 1: 실패하는 통합 테스트 작성** — `PasswordResetIntegrationTest.java` (옛 EmailVerificationIntegrationTest의 RecordingMailSender·고유 이메일/IP 관용구 이식. 레이트리밋이 전역 싱글턴이라 테스트마다 고유 이메일·고유 IP 필수)

```java
package com.celfit.was;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.celfit.was.mail.MailSendException;
import com.celfit.was.mail.MailSender;
import jakarta.servlet.http.Cookie;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 비밀번호 재설정 3단계(send→confirm→reset) 통합 테스트 — RecordingMailSender로 발송을
 * 가로채 코드를 캡처한다. 레이트리밋이 전역 싱글턴이라 테스트마다 고유 이메일을 쓰고,
 * IP 한도도 간섭하지 않도록 테스트 메서드마다 고유 remoteAddr을 부여한다.
 */
@AutoConfigureMockMvc
class PasswordResetIntegrationTest extends IntegrationTest {

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

	private static final AtomicInteger SEQUENCE = new AtomicInteger();

	private String email;
	private String testIp;

	@BeforeEach
	void setUp() {
		int seq = SEQUENCE.incrementAndGet();
		email = "pw-reset-" + seq + "@example.com";
		testIp = "10.9." + (seq / 250) + "." + (seq % 250 + 1);
		V1AuthTestSteps.enableSignupCode(jdbcClient);
	}

	// --- 헬퍼 ---

	/** 가입 헬퍼 — 반환 쿠키는 가입 시 자동 로그인된 세션(reset의 전 세션 무효화 검증에 사용). */
	private Cookie signUp() throws Exception {
		return V1AuthTestSteps.signUp(mockMvc, jdbcClient, email);
	}

	private org.springframework.test.web.servlet.ResultActions send() throws Exception {
		return mockMvc.perform(post("/v1/auth/password-reset/send").with(csrf())
				.with(req -> { req.setRemoteAddr(testIp); return req; })
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\": \"" + email + "\"}"));
	}

	private org.springframework.test.web.servlet.ResultActions confirm(String code) throws Exception {
		return mockMvc.perform(post("/v1/auth/password-reset/confirm").with(csrf())
				.with(req -> { req.setRemoteAddr(testIp); return req; })
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\": \"" + email + "\", \"code\": \"" + code + "\"}"));
	}

	private org.springframework.test.web.servlet.ResultActions reset(String token, String newPassword) throws Exception {
		return mockMvc.perform(post("/v1/auth/password-reset").with(csrf())
				.with(req -> { req.setRemoteAddr(testIp); return req; })
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"resetToken\": \"" + token + "\", \"newPassword\": \"" + newPassword + "\"}"));
	}

	/** send→confirm까지 완주하고 resetToken을 돌려준다. */
	private String issueToken() throws Exception {
		send().andExpect(status().isNoContent());
		String body = confirm(mail.lastCode()).andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		Matcher m = Pattern.compile("\"resetToken\"\\s*:\\s*\"([^\"]+)\"").matcher(body);
		assertThat(m.find()).isTrue();
		return m.group(1);
	}

	// --- send ---

	@Test
	void 가입_안_된_이메일_발송은_404_USER_NOT_FOUND() throws Exception {
		send().andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("USER_NOT_FOUND"));
	}

	@Test
	void 가입_이메일_발송은_204_코드_행_생성() throws Exception {
		signUp();
		send().andExpect(status().isNoContent());

		assertThat(mail.lastCode()).hasSize(6);
		Integer rows = jdbcClient.sql("SELECT count(*) FROM app.password_resets WHERE email = :email")
				.param("email", email).query(Integer.class).single();
		assertThat(rows).isEqualTo(1);
	}

	@Test
	void 재발송_쿨다운_60초는_429() throws Exception {
		signUp();
		send().andExpect(status().isNoContent());
		// 분 경계에 걸치면 2번째가 통과할 수 있다 — 그 경우 같은 분의 3번째로 판정(경계 2회 연속은 불가능)
		var second = send().andReturn();
		if (second.getResponse().getStatus() != 429) {
			send().andExpect(status().isTooManyRequests())
					.andExpect(jsonPath("$.error.code").value("RATE_LIMITED"));
		}
	}

	@Test
	void 발송_실패는_502_EMAIL_SEND_FAILED_행_미생성() throws Exception {
		signUp();
		mail.failNext = true;
		send().andExpect(status().isBadGateway())
				.andExpect(jsonPath("$.error.code").value("EMAIL_SEND_FAILED"));

		Integer rows = jdbcClient.sql("SELECT count(*) FROM app.password_resets WHERE email = :email")
				.param("email", email).query(Integer.class).single();
		assertThat(rows).isZero();
	}

	// --- confirm ---

	@Test
	void 코드_일치_confirm은_토큰을_발급하고_코드를_소모한다() throws Exception {
		signUp();
		send().andExpect(status().isNoContent());
		String code = mail.lastCode();

		confirm(code).andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data.resetToken").value(org.hamcrest.Matchers.startsWith("prt_")))
				.andExpect(jsonPath("$.data.expiresIn").value(600));

		// 같은 코드 재confirm은 소모돼 실패
		confirm(code).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("INVALID_VERIFICATION_CODE"));
	}

	@Test
	void 오입력_5회_누적_후에는_정답도_거부된다() throws Exception {
		signUp();
		send().andExpect(status().isNoContent());
		String code = mail.lastCode();

		for (int i = 0; i < 5; i++) {
			confirm("000000".equals(code) ? "111111" : "000000")
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.error.code").value("INVALID_VERIFICATION_CODE"));
		}
		confirm(code).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("INVALID_VERIFICATION_CODE"));
	}

	@Test
	void 만료된_코드는_거부된다() throws Exception {
		signUp();
		send().andExpect(status().isNoContent());
		jdbcClient.sql("UPDATE app.password_resets SET code_expires_at = now() - interval '1 second' WHERE email = :email")
				.param("email", email).update();

		confirm(mail.lastCode()).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("INVALID_VERIFICATION_CODE"));
	}

	// --- reset ---

	@Test
	void reset_성공은_204_비밀번호_교체_세션_전부_무효화_자동로그인_없음() throws Exception {
		Cookie oldSession = signUp();
		String token = issueToken();

		// 자동 로그인 없음 — 세션 쿠키 미발급(CSRF 쿠키가 있을 수 있어 SESSION만 단언)
		reset(token, "newPassw0rd").andExpect(status().isNoContent())
				.andExpect(cookie().doesNotExist("SESSION"));

		// 기존 세션 무효화 — 이전 세션 쿠키로 /v1/me가 더 이상 통하지 않는다
		mockMvc.perform(get("/v1/me").cookie(oldSession)).andExpect(status().isUnauthorized());
		// 새 비밀번호로 로그인 성공, 옛 비밀번호는 실패
		login("newPassw0rd").andExpect(status().isOk());
		login(V1AuthTestSteps.PASSWORD).andExpect(status().isUnauthorized());
		// 행 삭제(토큰 소비) 확인
		Integer rows = jdbcClient.sql("SELECT count(*) FROM app.password_resets WHERE email = :email")
				.param("email", email).query(Integer.class).single();
		assertThat(rows).isZero();
	}

	@Test
	void 토큰_재사용은_400_INVALID_RESET_TOKEN() throws Exception {
		signUp();
		String token = issueToken();
		reset(token, "newPassw0rd").andExpect(status().isNoContent());

		reset(token, "anotherPass1").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("INVALID_RESET_TOKEN"));
	}

	@Test
	void 만료된_토큰은_400_INVALID_RESET_TOKEN() throws Exception {
		signUp();
		String token = issueToken();
		jdbcClient.sql("UPDATE app.password_resets SET token_expires_at = now() - interval '1 second' WHERE email = :email")
				.param("email", email).update();

		reset(token, "newPassw0rd").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("INVALID_RESET_TOKEN"));
	}

	@Test
	void 위조_토큰은_400_INVALID_RESET_TOKEN() throws Exception {
		reset("prt_0000000000000000000000000000000000000000000000000000000000000000", "newPassw0rd")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("INVALID_RESET_TOKEN"));
	}

	@Test
	void 빈_비밀번호는_400_VALIDATION_FAILED_토큰은_생존한다() throws Exception {
		signUp();
		String token = issueToken();

		reset(token, "").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
		// 토큰이 소모되지 않아 재시도 성공
		reset(token, "newPassw0rd").andExpect(status().isNoContent());
	}

	private org.springframework.test.web.servlet.ResultActions login(String password) throws Exception {
		return mockMvc.perform(post("/v1/auth/login").with(csrf())
				.with(req -> { req.setRemoteAddr(testIp); return req; })
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\": \"" + email + "\", \"password\": \"" + password + "\"}"));
	}
}
```

- [ ] **Step 2: 미인증 응답 계약 확인** — `/v1/me` 미인증 응답이 401이 아니라 다른 코드면(예: 302) 실제 계약에 맞춰 `reset_성공...` 테스트의 기대값을 고친다 — LoginWallIntegrationTest에서 관용구 확인. import 목록의 `header`처럼 안 쓰는 static import는 정리.

- [ ] **Step 3: 실패 확인**

Run: `export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock && ./gradlew :was:test --tests "com.celfit.was.PasswordResetIntegrationTest"`
Expected: FAIL — 컨트롤러 부재로 404 (또는 컴파일 에러)

- [ ] **Step 4: V1ApiException에 코드 지정 404 오버로드 추가** (기존 notFound는 고정 코드 NOT_FOUND)

```java
	/** 코드 지정 404 — notFound()의 고정 코드(NOT_FOUND)와 달리 계약 코드를 직접 든다(예: USER_NOT_FOUND). */
	public static V1ApiException notFound(String code, String message) {
		return new V1ApiException(HttpStatus.NOT_FOUND, code, message);
	}
```

- [ ] **Step 5: 서비스 구현** — `PasswordResetService.java` (옛 EmailVerificationService 판정 순서 이식: 행 존재 → 코드 소모 여부·시도 한도·만료 → 해시 일치. 실패 사유 비구분 — 열거 방지)

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
 * 비밀번호 재설정(프론트 요청 2026-08-12) — 6자리 코드 발송·확인·1회용 토큰 발급·소비.
 * 옛 이메일 인증(설계 2026-07-18, cc14c717에서 철거)의 판정 순서를 이식했다.
 * 주 방어선은 코드 TTL 5분 + 오입력 5회 + 레이트리밋(컨트롤러). confirm·reset 실패는
 * 사유 비구분 단일 응답(부재·만료·시도 초과·불일치·재사용 동일 — 열거 방지).
 * 토큰은 저엔트로피 코드(6자리)를 confirm 시점에 소모하고 교환해 주는 고엔트로피
 * 1회용 자격(256비트, 해시 저장) — 코드 추측 공격 표면을 코드 TTL 안으로 좁힌다.
 */
@Service
public class PasswordResetService {

	static final Duration CODE_TTL = Duration.ofMinutes(5);
	static final Duration TOKEN_TTL = Duration.ofMinutes(10);
	static final int MAX_ATTEMPTS = 5;

	public record IssuedToken(String resetToken, int expiresIn) {
	}

	private final PasswordResetRepository repository;
	private final MailSender mailSender;
	private final Clock clock;
	private final SecureRandom random = new SecureRandom();

	public PasswordResetService(PasswordResetRepository repository, MailSender mailSender, Clock clock) {
		this.repository = repository;
		this.mailSender = mailSender;
		this.clock = clock;
	}

	/** 코드 생성→발송→저장. 발송 성공 후에만 저장(실패했는데 코드가 유효해지는 상태 방지). MailSendException은 컨트롤러가 502로 변환. */
	public void sendCode(String email) {
		String code = "%06d".formatted(random.nextInt(1_000_000));
		mailSender.send(email, "[hypenow] 비밀번호 재설정 인증번호",
				"""
				인증번호: %s

				5분 안에 재설정 화면에 입력해 주세요.
				본인이 요청하지 않았다면 이 메일을 무시하세요.""".formatted(code));
		repository.upsert(email, sha256(code), clock.instant().plus(CODE_TTL));
	}

	/**
	 * 판정 순서: 행 존재 → 코드 미소모·시도 한도·만료 → 해시 일치(불일치만 attempts 증가).
	 * 성공 시 코드를 소모하고 토큰 원문을 반환한다 — 원문은 이 응답이 유일한 노출(DB는 해시만).
	 */
	public IssuedToken confirm(String email, String code) {
		PasswordResetRepository.ResetChallenge row = repository.find(email)
				.orElseThrow(PasswordResetService::invalidCode);
		if (row.codeHash() == null || row.attempts() >= MAX_ATTEMPTS
				|| clock.instant().isAfter(row.codeExpiresAt().toInstant())) {
			throw invalidCode();
		}
		if (code == null || !row.codeHash().equals(sha256(code.trim()))) {
			repository.incrementAttempts(email);
			throw invalidCode();
		}
		byte[] bytes = new byte[32];
		random.nextBytes(bytes);
		String token = "prt_" + HexFormat.of().formatHex(bytes);
		repository.consumeCodeAndIssueToken(email, sha256(token), clock.instant().plus(TOKEN_TTL));
		return new IssuedToken(token, (int) TOKEN_TTL.toSeconds());
	}

	/**
	 * 토큰 검증 → 즉시 소비(행 삭제) → 소유 이메일 반환. 소비를 비밀번호 변경보다 먼저 해
	 * 어떤 실패 경로에서도 토큰이 두 번 쓰일 수 없다(중간 크래시는 유저가 처음부터 재진행).
	 */
	public String consumeToken(String resetToken) {
		if (resetToken == null || resetToken.isBlank()) {
			throw invalidToken();
		}
		PasswordResetRepository.ResetChallenge row = repository.findByTokenHash(sha256(resetToken.trim()))
				.orElseThrow(PasswordResetService::invalidToken);
		repository.delete(row.email());
		if (row.tokenExpiresAt() == null || clock.instant().isAfter(row.tokenExpiresAt().toInstant())) {
			throw invalidToken();
		}
		return row.email();
	}

	private static V1ApiException invalidCode() {
		return V1ApiException.badRequest("INVALID_VERIFICATION_CODE", "인증번호가 올바르지 않거나 만료됐어요.");
	}

	private static V1ApiException invalidToken() {
		return V1ApiException.badRequest("INVALID_RESET_TOKEN", "인증 시간이 만료됐어요. 처음부터 다시 진행해 주세요.");
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

- [ ] **Step 6: 컨트롤러 구현** — `V1PasswordResetController.java`

```java
package com.celfit.was.v1.account;

import com.celfit.was.auth.AppUser;
import com.celfit.was.auth.UserRepository;
import com.celfit.was.mail.MailSendException;
import com.celfit.was.v1.common.ApiResponse;
import com.celfit.was.v1.common.V1ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * /v1/auth/password-reset 3종(프론트 요청 2026-08-12, 스펙 6.34 예정) — 로그인 불가 유저의
 * 자가 복구. 익명 표면(화이트리스트 /v1/auth/**)이라 레이트리밋이 1차 방어. 가입 안 된
 * 이메일은 404로 즉시 알린다 — 존재 노출은 email-availability(6.24)와 동일 수준이고,
 * 오지 않는 메일을 기다리는 오타 유저의 손실이 더 크다(요청서 3절 결정).
 */
@RestController
public class V1PasswordResetController {

	private static final Logger log = LoggerFactory.getLogger(V1PasswordResetController.class);

	public record SendRequest(String email) {
	}

	public record ConfirmRequest(String email, String code) {
	}

	public record ResetRequest(String resetToken, String newPassword) {
	}

	public record ConfirmResponse(String resetToken, int expiresIn) {
	}

	private final PasswordResetService passwordResetService;
	private final SignupValidator signupValidator;
	private final RateLimiter rateLimiter;
	private final UserRepository userRepository;
	private final SessionService sessionService;
	private final PasswordEncoder passwordEncoder;

	public V1PasswordResetController(PasswordResetService passwordResetService,
			SignupValidator signupValidator, RateLimiter rateLimiter, UserRepository userRepository,
			SessionService sessionService, PasswordEncoder passwordEncoder) {
		this.passwordResetService = passwordResetService;
		this.signupValidator = signupValidator;
		this.rateLimiter = rateLimiter;
		this.userRepository = userRepository;
		this.sessionService = sessionService;
		this.passwordEncoder = passwordEncoder;
	}

	@PostMapping("/v1/auth/password-reset/send")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void send(@RequestBody SendRequest request, HttpServletRequest httpRequest) {
		String email = request.email() == null ? "" : UserRepository.normalizeEmail(request.email());
		// 요청서 3절 정책 — 쿨다운 60초(이메일당 분당 1회) + 이메일당 시간당 5회 + IP당 시간당 20회
		if (!rateLimiter.tryAcquire("pw-reset-send:" + email, 1)
				|| !rateLimiter.tryAcquire("pw-reset-send-1h:" + email, 5, 60)
				|| !rateLimiter.tryAcquire("pw-reset-send-ip-1h:" + httpRequest.getRemoteAddr(), 20, 60)) {
			throw V1ApiException.rateLimited();
		}
		signupValidator.requireEmail(request.email());
		if (userRepository.findByEmail(email).isEmpty()) {
			throw V1ApiException.notFound("USER_NOT_FOUND", "가입되지 않은 이메일이에요.");
		}
		try {
			passwordResetService.sendCode(email);
		} catch (MailSendException e) {
			throw V1ApiException.badGateway("EMAIL_SEND_FAILED", "메일 발송에 실패했어요. 잠시 후 다시 시도해 주세요.");
		}
	}

	@PostMapping("/v1/auth/password-reset/confirm")
	public ApiResponse<ConfirmResponse> confirm(@RequestBody ConfirmRequest request,
			HttpServletRequest httpRequest) {
		// 코드 무차별 대입 2차 방어(1차는 오입력 5회) — 익명 표면이라 키는 IP 단위
		if (!rateLimiter.tryAcquire("pw-reset-confirm:" + httpRequest.getRemoteAddr())) {
			throw V1ApiException.rateLimited();
		}
		signupValidator.requireEmail(request.email());
		PasswordResetService.IssuedToken issued = passwordResetService
				.confirm(UserRepository.normalizeEmail(request.email()), request.code());
		return ApiResponse.ok(new ConfirmResponse(issued.resetToken(), issued.expiresIn()));
	}

	/** 성공 시 자동 로그인 없음(Set-Cookie 미발급) — 프론트가 로그인 화면으로 보낸다(요청서 5절). */
	@PostMapping("/v1/auth/password-reset")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void reset(@RequestBody ResetRequest request, HttpServletRequest httpRequest) {
		if (!rateLimiter.tryAcquire("pw-reset:" + httpRequest.getRemoteAddr())) {
			throw V1ApiException.rateLimited();
		}
		// 검증을 토큰 소비보다 먼저 — 검증 실패로 토큰이 죽으면 유저가 처음부터 다시 해야 한다
		signupValidator.validatePassword(request.newPassword());
		String email = passwordResetService.consumeToken(request.resetToken());
		AppUser user = userRepository.findByEmail(email)
				.orElseThrow(() -> V1ApiException.badRequest("INVALID_RESET_TOKEN",
						"인증 시간이 만료됐어요. 처음부터 다시 진행해 주세요."));
		userRepository.updatePasswordHash(user.id(), passwordEncoder.encode(request.newPassword()));
		// 탈취 세션 차단(요청서 5절). DB(password_hash)가 정본 — 정리 실패로 500을 내리면
		// 클라이언트가 "재설정 실패"로 오해하므로 best-effort(6.13 관용구, V1MeController 참조)
		try {
			sessionService.deleteAll(email);
		} catch (RuntimeException e) {
			log.warn("비밀번호 재설정은 완료, 세션 무효화 실패 — userId={}", user.id(), e);
		}
	}
}
```

- [ ] **Step 7: 통과 확인**

Run: `export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock && ./gradlew :was:test --tests "com.celfit.was.PasswordResetIntegrationTest"`
Expected: PASS 12개

- [ ] **Step 8: Commit**

```bash
git add was/src/main/java/com/celfit/was/v1/account/PasswordResetService.java was/src/main/java/com/celfit/was/v1/account/V1PasswordResetController.java was/src/main/java/com/celfit/was/v1/common/V1ApiException.java was/src/test/java/com/celfit/was/PasswordResetIntegrationTest.java
git commit -m "feat(was): 비밀번호 재설정 3종 — send(404 열거 허용)·confirm(1회용 토큰)·reset(전 세션 무효화)"
```

---

### Task 5: 모듈 전체 테스트 + 문서

**Files:**
- Modify: `DECISIONS.md` (맨 위에 결정 추가)

- [ ] **Step 1: was 모듈 전체 테스트** (colima 자원 확인 — CLAUDE.md 빌드·검증 절)

Run: `export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock && ./gradlew :was:test`
Expected: 전부 PASS. 실패 시 원인 규명 전 진행 금지(대량 실패면 DOCKER_HOST부터 확인).

- [ ] **Step 2: DECISIONS.md 맨 위에 결정 기록** (형식은 기존 항목을 따른다)

핵심 내용: 비밀번호 재설정 3종 신설(프론트 요청 2026-08-12). mail 스택은 cc14c717 철거분 복원 — 철거 사유(임의 주소 발송 남용)는 가입된 이메일 한정 발송(404)이라 비성립. 404 열거 허용은 email-availability(6.24)와 동일 노출 수준 + 레이트리밋(60초 쿨다운·시간당 이메일 5회/IP 20회)으로 상쇄. 토큰은 confirm 시점에 6자리 코드를 소모하고 교환하는 256비트 1회용(해시 저장, TTL 10분). reset 성공 시 세션 전부 무효화(best-effort, 6.13 관용구). 502 EMAIL_SEND_FAILED는 요청서에 없으나 6.17 관용구 유지 — 프론트 회신 필요.

- [ ] **Step 3: Commit + push** (PR은 열지 않는다 — 사용자 승인 대기)

```bash
git add DECISIONS.md
git commit -m "docs: 비밀번호 재설정 결정 기록 — mail 스택 복원 근거·404 열거 허용·토큰 교환 설계"
git push -u origin feat/password-reset
```

---

## 프론트 회신 사항 (구현 후 전달)

1. **6.17은 07-29에 철거됐고 이번이 실발송 기능 복원이다.** 발송은 Resend HTTPS(기존 인프라 복원) — 단, **test·운영 서버 `.env`에 `RESEND_API_KEY` 등록 여부를 배포 전 확인해야 한다**(미설정이면 로깅 폴백으로 실발송 없음). Resend 대시보드에서 hypenow.io 도메인 인증(SPF/DKIM) 상태도 확인.
2. 정책 값은 제안대로 수용(만료 5분, 쿨다운 60초, 이메일 시간당 5회, IP 시간당 20회, 오입력 5회).
3. 세션 전부 무효화 가능 — Spring Session principal 인덱스로 구현.
4. `resetToken`·`expiresIn` 네이밍은 기존 관례(camelCase envelope)와 일치. 에러 코드 3종 모두 기존과 충돌 없음.
5. **추가 에러 1건**: send에서 메일 발송 자체가 실패하면 502 `EMAIL_SEND_FAILED`(6.17 시절 관용구 유지). 스펙 6.34 반영 시 포함 요청.
6. confirm·reset에도 IP당 분당 10회 레이트리밋(429)이 걸린다.
