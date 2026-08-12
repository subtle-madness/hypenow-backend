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
