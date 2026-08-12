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
		repository.consumeCodeAndIssueToken(EMAIL, "code-hash-1", "token-hash-1", Instant.now().plusSeconds(600));

		repository.upsert(EMAIL, "code-hash-2", Instant.now().plusSeconds(300));

		var row = repository.find(EMAIL).orElseThrow();
		assertThat(row.codeHash()).isEqualTo("code-hash-2");
		assertThat(row.attempts()).isZero();
		assertThat(row.tokenHash()).isNull();
		assertThat(row.tokenExpiresAt()).isNull();
	}

	@Test
	void 코드_일치_소모는_성공하고_불일치는_거부된다() {
		repository.upsert(EMAIL, "code-hash-1", Instant.now().plusSeconds(300));
		Instant tokenExpiresAt = Instant.now().plusSeconds(600);

		// 불일치 코드 해시로는 소모되지 않는다(영향 0행 → false, 행도 그대로)
		assertThat(repository.consumeCodeAndIssueToken(EMAIL, "code-hash-틀림", "token-hash-1", tokenExpiresAt))
				.isFalse();
		assertThat(repository.find(EMAIL).orElseThrow().codeHash()).isEqualTo("code-hash-1");

		assertThat(repository.consumeCodeAndIssueToken(EMAIL, "code-hash-1", "token-hash-1", tokenExpiresAt))
				.isTrue();
		var row = repository.find(EMAIL).orElseThrow();
		assertThat(row.codeHash()).isNull(); // 코드 소모 — 같은 코드 재confirm 불가
		assertThat(row.tokenHash()).isEqualTo("token-hash-1");
		assertThat(row.tokenExpiresAt().toInstant()).isCloseTo(tokenExpiresAt, within(1, ChronoUnit.SECONDS));

		// 이미 소모된 코드로 재confirm 시도 — code_hash가 이미 NULL이라 WHERE 불일치, 다시 false
		assertThat(repository.consumeCodeAndIssueToken(EMAIL, "code-hash-1", "token-hash-2", tokenExpiresAt))
				.isFalse();
	}

	@Test
	void claim은_토큰_행을_원자적으로_삭제하고_반환한다() {
		repository.upsert(EMAIL, "code-hash-1", Instant.now().plusSeconds(300));
		Instant tokenExpiresAt = Instant.now().plusSeconds(600);
		repository.consumeCodeAndIssueToken(EMAIL, "code-hash-1", "token-hash-1", tokenExpiresAt);

		var claimed = repository.claimByTokenHash("token-hash-1").orElseThrow();
		assertThat(claimed.email()).isEqualTo(EMAIL);
		assertThat(claimed.tokenExpiresAt().toInstant()).isCloseTo(tokenExpiresAt, within(1, ChronoUnit.SECONDS));
		// claim이 곧 삭제 — 행이 사라졌다
		assertThat(repository.find(EMAIL)).isEmpty();
	}

	@Test
	void 없는_토큰_해시_claim은_빈_값이다() {
		assertThat(repository.claimByTokenHash("없는-해시")).isEmpty();
	}

	@Test
	void 같은_토큰_이중_claim은_두_번째가_빈_값이다() {
		repository.upsert(EMAIL, "code-hash-1", Instant.now().plusSeconds(300));
		repository.consumeCodeAndIssueToken(EMAIL, "code-hash-1", "token-hash-1", Instant.now().plusSeconds(600));

		assertThat(repository.claimByTokenHash("token-hash-1")).isPresent();
		// 첫 claim이 행을 지웠으므로 두 번째는 동시 요청이든 재사용 시도든 항상 빈 값
		assertThat(repository.claimByTokenHash("token-hash-1")).isEmpty();
	}
}
