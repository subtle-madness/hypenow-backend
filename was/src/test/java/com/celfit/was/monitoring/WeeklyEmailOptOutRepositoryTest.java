package com.celfit.was.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.was.IntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** 주간 리포트 메일 옵트아웃 — 행 없음 = 수신(기본 on), 행 있음 = 수신 거부(설계 §5). */
class WeeklyEmailOptOutRepositoryTest extends IntegrationTest {

	@Autowired
	WeeklyEmailOptOutRepository repository;
	@Autowired
	JdbcClient jdbcClient;

	long userId;

	@BeforeEach
	void 유저_시드() {
		userId = jdbcClient.sql("INSERT INTO app.users (email, password_hash) VALUES (:email, 'x') RETURNING id")
				.param("email", "weekly-optout-" + UUID.randomUUID() + "@test.io")
				.query(Long.class).single();
	}

	@Test
	void 기본_상태는_수신이다() {
		assertThat(repository.isOptedOut(userId)).isFalse();
	}

	@Test
	void optOut_왕복() {
		repository.optOut(userId);

		assertThat(repository.isOptedOut(userId)).isTrue();
	}

	@Test
	void optOut_두_번_호출해도_멱등() {
		repository.optOut(userId);
		repository.optOut(userId);

		assertThat(repository.isOptedOut(userId)).isTrue();
		assertThat(jdbcClient.sql("""
				SELECT count(*) FROM app.monitoring_email_opt_outs
				WHERE user_id = :userId AND event_type = 'WEEKLY_DIGEST'
				""").param("userId", userId).query(Long.class).single()).isEqualTo(1);
	}

	@Test
	void optIn_후_다시_수신() {
		repository.optOut(userId);
		repository.optIn(userId);

		assertThat(repository.isOptedOut(userId)).isFalse();
	}

	@Test
	void optIn_행이_없어도_에러_없이_통과() {
		repository.optIn(userId);

		assertThat(repository.isOptedOut(userId)).isFalse();
	}

	@Test
	void 구_4종_옵트아웃_행은_주간_판정에_영향을_주지_않는다() {
		// 이관은 마이그레이션이 이미 끝냈다 — 이후 새로 생긴 구 어휘 행이 주간 토글을 오염시키면 안 된다.
		jdbcClient.sql("""
				INSERT INTO app.monitoring_email_opt_outs (user_id, event_type)
				VALUES (:userId, 'COLLECTION_ENDED')
				""").param("userId", userId).update();

		assertThat(repository.isOptedOut(userId)).isFalse();
	}
}
