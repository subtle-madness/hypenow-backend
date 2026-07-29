package com.celfit.was.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.celfit.was.IntegrationTest;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

class MonitoringAlarmRepositoryTest extends IntegrationTest {

	@Autowired
	MonitoringAlarmRepository repository;
	@Autowired
	JdbcClient jdbcClient;

	long userA;
	long userB;

	@BeforeEach
	void 유저_시드() {
		userA = seedUser();
		userB = seedUser();
	}

	long seedUser() {
		return jdbcClient.sql("""
				INSERT INTO app.users (email, password_hash) VALUES (:email, 'x') RETURNING id
				""")
				.param("email", "alarm-" + UUID.randomUUID() + "@test.io")
				.query(Long.class).single();
	}

	@Test
	void 옵트아웃_행이_없으면_아무도_제외되지_않는다() {
		assertThat(repository.optedOutUserIds("POST_DETECTED", List.of(userA, userB))).isEmpty();
		assertThat(repository.optedOutUserIds("POST_DETECTED", List.of())).isEmpty();
	}

	@Test
	void 옵트아웃한_유저만_해당_이벤트에서_제외된다() {
		jdbcClient.sql("""
				INSERT INTO app.monitoring_email_opt_outs (user_id, event_type)
				VALUES (:u, 'POST_DETECTED'), (:u2, 'MONITORING_ENDED')
				""").param("u", userA).param("u2", userB).update();

		Set<Long> optedOut = repository.optedOutUserIds("POST_DETECTED", List.of(userA, userB));

		assertThat(optedOut).containsExactly(userA);   // userB는 다른 이벤트만 껐다
	}

	// 주의: monitoring_alarm_state는 공유 컨테이너 전역 1행 — 이 테스트는 상대값(seeded 기준)으로만
	// 단언한다. 이 테이블을 건드리는 새 테스트는 절대값 단언 전에 반드시 자체 리셋할 것.
	@Test
	void 워터마크는_시드돼_있고_전진만_허용된다() {
		OffsetDateTime seeded = repository.watermark("POST_DETECTED");
		assertThat(seeded).isNotNull();

		OffsetDateTime future = seeded.plusDays(1);
		repository.advanceWatermark("POST_DETECTED", future);
		assertThat(repository.watermark("POST_DETECTED")).isEqualTo(future);

		// 과거 값으로 호출해도 후퇴하지 않는다
		repository.advanceWatermark("POST_DETECTED", seeded);
		assertThat(repository.watermark("POST_DETECTED")).isEqualTo(future);
	}

	@Test
	void 유저_이메일_일괄_조회() {
		Map<Long, String> emails = repository.emailsByUserIds(List.of(userA, userB));

		assertThat(emails).hasSize(2);
		assertThat(emails.get(userA)).contains("@test.io");
		assertThat(repository.emailsByUserIds(List.of())).isEmpty();
	}

	@Test
	void 정의되지_않은_이벤트_타입은_DB가_거부한다() {
		assertThatThrownBy(() -> jdbcClient.sql("""
				INSERT INTO app.monitoring_email_opt_outs (user_id, event_type)
				VALUES (:u, 'NOT_A_REAL_EVENT')
				""").param("u", userA).update())
				.isInstanceOf(DataIntegrityViolationException.class);
	}
}
