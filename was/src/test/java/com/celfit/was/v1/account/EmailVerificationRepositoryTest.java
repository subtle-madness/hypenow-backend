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
		assertThat(row.codeExpiresAt().toInstant()).isCloseTo(expiresAt, org.assertj.core.api.Assertions.within(1, java.time.temporal.ChronoUnit.SECONDS));
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
		assertThat(repository.find(EMAIL).orElseThrow().verifiedAt().toInstant())
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
