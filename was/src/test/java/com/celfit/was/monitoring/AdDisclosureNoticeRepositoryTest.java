package com.celfit.was.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.was.IntegrationTest;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 미표기 판정 알림 이력 — 게시물당 1회 가드(설계 §8). 같은 주 재실행은 자기 이력에 걸리지
 * 않아야 하고(멱등), 다른 주의 재판정분은 걸러져야 한다.
 */
class AdDisclosureNoticeRepositoryTest extends IntegrationTest {

	private static final LocalDate WEEK = LocalDate.of(2026, 8, 17);
	private static final LocalDate NEXT_WEEK = LocalDate.of(2026, 8, 24);

	@Autowired
	AdDisclosureNoticeRepository repository;
	@Autowired
	JdbcClient jdbcClient;

	long userId;

	@BeforeEach
	void 유저_시드() {
		userId = jdbcClient.sql("INSERT INTO app.users (email, password_hash) VALUES (:email, 'x') RETURNING id")
				.param("email", "ad-notice-" + UUID.randomUUID() + "@test.io")
				.query(Long.class).single();
	}

	@Test
	void 이력이_없으면_걸러낼_대상도_없다() {
		assertThat(repository.findNotifiedInOtherWeek(userId, List.of("SC1", "SC2"), WEEK)).isEmpty();
	}

	@Test
	void 빈_입력은_조회하지_않고_빈_집합() {
		assertThat(repository.findNotifiedInOtherWeek(userId, List.of(), WEEK)).isEmpty();
	}

	@Test
	void 같은_주에_기록한_이력은_그_주_재실행에서_걸러지지_않는다() {
		repository.markNotified(userId, List.of("SC1"), WEEK);

		assertThat(repository.findNotifiedInOtherWeek(userId, List.of("SC1"), WEEK)).isEmpty();
	}

	@Test
	void 다른_주에_기록한_이력은_이번_주_후보에서_걸러진다() {
		repository.markNotified(userId, List.of("SC1"), WEEK);

		assertThat(repository.findNotifiedInOtherWeek(userId, List.of("SC1", "SC2"), NEXT_WEEK))
				.containsExactly("SC1");
	}

	@Test
	void markNotified는_멱등이며_최초_주를_보존한다() {
		repository.markNotified(userId, List.of("SC1"), WEEK);
		repository.markNotified(userId, List.of("SC1"), NEXT_WEEK);

		assertThat(jdbcClient.sql("""
				SELECT notified_week FROM app.ad_disclosure_notices
				WHERE user_id = :userId AND short_code = 'SC1'
				""").param("userId", userId).query(LocalDate.class).single()).isEqualTo(WEEK);
	}

	@Test
	void 다른_유저의_이력은_섞이지_않는다() {
		long otherUserId = jdbcClient
				.sql("INSERT INTO app.users (email, password_hash) VALUES (:email, 'x') RETURNING id")
				.param("email", "ad-notice-other-" + UUID.randomUUID() + "@test.io")
				.query(Long.class).single();
		repository.markNotified(otherUserId, List.of("SC1"), WEEK);

		assertThat(repository.findNotifiedInOtherWeek(userId, List.of("SC1"), NEXT_WEEK)).isEmpty();
	}
}
