package com.celfit.was.v1.account;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.was.IntegrationTest;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * PasswordResetSweepScheduler 통합 테스트 — 코드·토큰이 모두 만료+유예 1일 경과한 행만
 * 삭제되는지 확인한다(스케줄러 클래스 문서의 삭제 조건 참조).
 */
class PasswordResetSweepSchedulerTest extends IntegrationTest {

	@Autowired
	PasswordResetSweepScheduler scheduler;

	@Autowired
	JdbcClient jdbcClient;

	@BeforeEach
	void clean() {
		jdbcClient.sql("DELETE FROM app.password_resets").update();
	}

	private void insert(String email, OffsetDateTime codeExpiresAt, OffsetDateTime tokenExpiresAt) {
		jdbcClient.sql("""
				INSERT INTO app.password_resets (email, code_hash, code_expires_at, token_hash, token_expires_at)
				VALUES (:email, 'code-hash', :codeExpiresAt, :tokenHash, :tokenExpiresAt)""")
				.param("email", email)
				.param("codeExpiresAt", codeExpiresAt)
				.param("tokenHash", tokenExpiresAt == null ? null : "token-hash-" + email)
				.param("tokenExpiresAt", tokenExpiresAt)
				.update();
	}

	private boolean exists(String email) {
		return jdbcClient.sql("SELECT count(*) FROM app.password_resets WHERE email = :email")
				.param("email", email)
				.query(Long.class)
				.single() > 0;
	}

	@Test
	void 코드와_토큰이_모두_만료되고_유예_1일이_지난_행은_삭제된다() {
		OffsetDateTime now = OffsetDateTime.now();
		insert("gone@example.com", now.minusDays(2), null);

		scheduler.sweep();

		assertThat(exists("gone@example.com")).isFalse();
	}

	@Test
	void 코드가_아직_유효한_행은_보존된다() {
		OffsetDateTime now = OffsetDateTime.now();
		insert("code-live@example.com", now.plusDays(1), null);

		scheduler.sweep();

		assertThat(exists("code-live@example.com")).isTrue();
	}

	@Test
	void 코드가_만료됐지만_유예_1일이_안_지난_행은_보존된다() {
		OffsetDateTime now = OffsetDateTime.now();
		insert("grace-window@example.com", now.minusHours(12), null);

		scheduler.sweep();

		assertThat(exists("grace-window@example.com")).isTrue();
	}

	@Test
	void 토큰이_아직_살아있는_행은_코드가_오래_만료됐어도_보존된다() {
		OffsetDateTime now = OffsetDateTime.now();
		insert("token-live@example.com", now.minusDays(3), now.plusDays(1));

		scheduler.sweep();

		assertThat(exists("token-live@example.com")).isTrue();
	}
}
