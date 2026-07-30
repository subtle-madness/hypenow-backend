package com.celfit.was.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.was.IntegrationTest;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

class DigestRepositoryTest extends IntegrationTest {

	@Autowired
	DigestRepository repository;
	@Autowired
	JdbcClient jdbcClient;

	long userId;

	@BeforeEach
	void 유저_시드() {
		userId = seedUser();
	}

	private long seedUser() {
		return jdbcClient.sql("INSERT INTO app.users (email, password_hash) VALUES (:email, 'x') RETURNING id")
				.param("email", "mon-digest-" + UUID.randomUUID() + "@test.io")
				.query(Long.class).single();
	}

	@Test
	void insertIfAbsent_같은_user_date_중복은_무시된다() {
		LocalDate date = LocalDate.of(2026, 7, 29);

		Optional<Long> first = repository.insertIfAbsent(userId, date, "[]");
		Optional<Long> second = repository.insertIfAbsent(userId, date, "[]");

		assertThat(first).isPresent();
		assertThat(second).isEmpty();
		assertThat(repository.countByUser(userId)).isEqualTo(1);
	}

	@Test
	void findRecentByUser_정렬과_limit() {
		repository.insertIfAbsent(userId, LocalDate.of(2026, 7, 27), "[]");
		repository.insertIfAbsent(userId, LocalDate.of(2026, 7, 29), "[]");
		repository.insertIfAbsent(userId, LocalDate.of(2026, 7, 28), "[]");

		List<DigestRow> recent = repository.findRecentByUser(userId, 2);

		assertThat(recent).extracting(DigestRow::digestDate)
				.containsExactly(LocalDate.of(2026, 7, 29), LocalDate.of(2026, 7, 28));
	}

	@Test
	void markRead는_본인_소유_행만_처리한다() {
		long otherUserId = seedUser();
		long myDigestId = repository.insertIfAbsent(userId, LocalDate.of(2026, 7, 29), "[]").orElseThrow();
		long otherDigestId = repository.insertIfAbsent(otherUserId, LocalDate.of(2026, 7, 29), "[]").orElseThrow();

		// 타 유저 id·존재하지 않는 id를 섞어도 본인 것만 읽음 처리된다(멱등, 404·403 없음).
		repository.markRead(userId, List.of(myDigestId, otherDigestId, 999_999L));

		assertThat(repository.findRecentByUser(userId, 10).get(0).readAt()).isNotNull();
		assertThat(repository.findRecentByUser(otherUserId, 10).get(0).readAt()).isNull();
	}

	@Test
	void markRead는_멱등이며_최초_읽음_시각을_보존한다() throws InterruptedException {
		long digestId = repository.insertIfAbsent(userId, LocalDate.of(2026, 7, 29), "[]").orElseThrow();

		repository.markRead(userId, List.of(digestId));
		OffsetDateTime firstReadAt = repository.findRecentByUser(userId, 10).get(0).readAt();
		assertThat(firstReadAt).isNotNull();

		Thread.sleep(10); // now()가 다른 값을 찍을 여유를 준다 — 재적용돼도 안 바뀌어야 한다.
		repository.markRead(userId, List.of(digestId));
		OffsetDateTime secondReadAt = repository.findRecentByUser(userId, 10).get(0).readAt();

		assertThat(secondReadAt).isEqualTo(firstReadAt);
	}

	@Test
	void markRead_빈_리스트는_no_op() {
		long digestId = repository.insertIfAbsent(userId, LocalDate.of(2026, 7, 29), "[]").orElseThrow();

		repository.markRead(userId, List.of());

		assertThat(repository.findRecentByUser(userId, 10).get(0).readAt()).isNull();
	}

	@Test
	void markAllRead는_응답_창_30건_밖의_행까지_전부_읽음_처리한다() {
		LocalDate base = LocalDate.of(2026, 1, 1);
		for (int i = 0; i < 31; i++) {
			repository.insertIfAbsent(userId, base.plusDays(i), "[]");
		}
		assertThat(repository.countByUser(userId)).isEqualTo(31);

		repository.markAllRead(userId);

		long unreadCount = jdbcClient.sql(
				"SELECT count(*) FROM app.monitoring_digests WHERE user_id = :userId AND read_at IS NULL")
				.param("userId", userId)
				.query(Long.class)
				.single();
		assertThat(unreadCount).isZero();
	}

	@Test
	void countByUser() {
		assertThat(repository.countByUser(userId)).isZero();

		repository.insertIfAbsent(userId, LocalDate.of(2026, 7, 29), "[]");
		repository.insertIfAbsent(userId, LocalDate.of(2026, 7, 28), "[]");

		assertThat(repository.countByUser(userId)).isEqualTo(2);
	}
}
