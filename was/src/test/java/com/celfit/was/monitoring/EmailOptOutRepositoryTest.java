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

	@Test
	void WEEKLY_DIGEST_행이_섞여_있어도_NPE_없이_4종_매트릭스와_무관하게_걸러진다() {
		// 2026-08-28 재리뷰 Critical 회귀 — V20260827135725가 기존 옵트아웃 유저 전원에게
		// WEEKLY_DIGEST 행을 백필했다. toFront가 미지 어휘에 null을 돌려주므로(WEEKLY_DIGEST는
		// 4종 매트릭스 어휘가 아니다) 필터링 없이는 Collectors.toUnmodifiableSet()이 NPE를 던진다.
		repository.optOut(userId, "collection_ended");
		jdbcClient.sql("""
				INSERT INTO app.monitoring_email_opt_outs (user_id, event_type) VALUES (:userId, 'WEEKLY_DIGEST')
				""")
				.param("userId", userId)
				.update();

		assertThat(repository.findOptOuts(userId)).containsExactly("collection_ended");
	}
}
