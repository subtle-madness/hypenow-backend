package com.celfit.was;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.was.v1.account.SignupEventRetentionScheduler;
import java.time.Clock;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * SignupEventRetentionScheduler — 90일 경과 행 삭제 검증(트랙 A 스펙 §signup_events).
 * 91일 전 행은 삭제, 89일 전 행은 보존 확인.
 */
class SignupEventRetentionTest extends IntegrationTest {

	@Autowired JdbcClient jdbcClient;
	@Autowired Clock clock;

	@Test
	void deleteExpired_91일_경과_행_삭제_89일_행_보존() {
		// 준비: 91일 전, 89일 전 행 시드
		OffsetDateTime now = OffsetDateTime.now(clock);
		OffsetDateTime ninetyOneDaysAgo = now.minusDays(91);
		OffsetDateTime eightyNineDaysAgo = now.minusDays(89);

		// 91일 전 행 (삭제되어야 함)
		jdbcClient.sql("INSERT INTO app.signup_events (email, outcome, ip, detail, created_at) " +
				"VALUES (:email, :outcome, :ip, :detail, :created_at)")
				.param("email", "old@example.com")
				.param("outcome", "ok")
				.param("ip", "203.0.113.1")
				.param("detail", null)
				.param("created_at", ninetyOneDaysAgo)
				.update();

		// 89일 전 행 (보존되어야 함)
		jdbcClient.sql("INSERT INTO app.signup_events (email, outcome, ip, detail, created_at) " +
				"VALUES (:email, :outcome, :ip, :detail, :created_at)")
				.param("email", "recent@example.com")
				.param("outcome", "ok")
				.param("ip", "203.0.113.2")
				.param("detail", null)
				.param("created_at", eightyNineDaysAgo)
				.update();

		// 스케줄러 호출
		SignupEventRetentionScheduler scheduler = new SignupEventRetentionScheduler(jdbcClient, clock);
		scheduler.deleteExpired();

		// 검증: 91일 전 행은 삭제되고, 89일 전 행은 남아있어야 함
		long oldRowCount = jdbcClient.sql("SELECT COUNT(*) FROM app.signup_events WHERE email = 'old@example.com'")
				.query(Long.class).single();
		long recentRowCount = jdbcClient.sql("SELECT COUNT(*) FROM app.signup_events WHERE email = 'recent@example.com'")
				.query(Long.class).single();

		assertThat(oldRowCount).isZero();
		assertThat(recentRowCount).isEqualTo(1);
	}
}
