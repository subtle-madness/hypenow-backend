package com.celfit.was.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.was.IntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

class EmailOptOutRepositoryTest extends IntegrationTest {

	@Autowired
	EmailOptOutRepository repository;
	@Autowired
	JdbcClient jdbcClient;

	long userId;

	@BeforeEach
	void 유저_시드() {
		userId = jdbcClient.sql("INSERT INTO app.users (email, password_hash) VALUES (:email, 'x') RETURNING id")
				.param("email", "mon-optout-" + UUID.randomUUID() + "@test.io")
				.query(Long.class).single();
	}

	@Test
	void 기본_상태는_옵트아웃_없음() {
		assertThat(repository.findOptOuts(userId)).isEmpty();
	}

	@Test
	void optOut_왕복() {
		repository.optOut(userId, "collection_ended");

		assertThat(repository.findOptOuts(userId)).containsExactly("collection_ended");
	}

	@Test
	void optOut_두_번_호출해도_멱등() {
		repository.optOut(userId, "collection_ended");
		repository.optOut(userId, "collection_ended");

		assertThat(repository.findOptOuts(userId)).containsExactly("collection_ended");
	}

	@Test
	void optIn_후_빈_집합() {
		repository.optOut(userId, "collection_ended");
		repository.optIn(userId, "collection_ended");

		assertThat(repository.findOptOuts(userId)).isEmpty();
	}

	@Test
	void optIn_행이_없어도_에러_없이_통과() {
		repository.optIn(userId, "collection_ended");

		assertThat(repository.findOptOuts(userId)).isEmpty();
	}

	@Test
	void 여러_이벤트_동시_옵트아웃() {
		repository.optOut(userId, "collection_ended");
		repository.optOut(userId, "content_issue");

		assertThat(repository.findOptOuts(userId)).containsExactlyInAnyOrder("collection_ended", "content_issue");
	}
}
