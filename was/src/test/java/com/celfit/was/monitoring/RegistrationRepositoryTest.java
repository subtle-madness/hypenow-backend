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

class RegistrationRepositoryTest extends IntegrationTest {

	@Autowired
	RegistrationRepository repository;
	@Autowired
	MonitoringItemRepository itemRepository;
	@Autowired
	JdbcClient jdbcClient;

	long userId;

	@BeforeEach
	void 유저_시드() {
		userId = jdbcClient.sql("INSERT INTO app.users (email, password_hash) VALUES (:email, 'x') RETURNING id")
				.param("email", "mon-reg-" + UUID.randomUUID() + "@test.io")
				.query(Long.class).single();
	}

	@Test
	void entry_seq_순서_보존() {
		long regId = repository.insert(userId);
		repository.insertEntry(regId, 2, "second", "post", "pending", null, null, null, null);
		repository.insertEntry(regId, 1, "first", "post", "pending", null, null, null, null);
		repository.insertEntry(regId, 3, "third", "post", "pending", null, null, null, null);

		RegistrationRow row = repository.findRecentByUser(userId, 10).get(0);
		assertThat(row.entries()).extracting(RegistrationEntryRow::input).containsExactly("first", "second", "third");
	}

	@Test
	void updateEntryResult_왕복() {
		long itemId = itemRepository.insertPending(userId, "url", UUID.randomUUID(), null, "abc123",
				"https://x/abc123", null, 30, LocalDate.of(2026, 7, 30));
		long regId = repository.insert(userId);
		repository.insertEntry(regId, 1, "abc123", "post", "pending", null, null, null, null);

		repository.updateEntryResult(regId, 1, "success", null, null, "https://x/abc123", itemId);

		RegistrationEntryRow entry = repository.findRecentByUser(userId, 10).get(0).entries().get(0);
		assertThat(entry.result()).isEqualTo("success");
		assertThat(entry.resolvedUrl()).isEqualTo("https://x/abc123");
		assertThat(entry.itemId()).isEqualTo(itemId);
	}

	@Test
	void markCompletedIfAllSettled_pending_남으면_미완료() {
		long regId = repository.insert(userId);
		repository.insertEntry(regId, 1, "a", "post", "success", null, null, null, null);
		repository.insertEntry(regId, 2, "b", "post", "pending", null, null, null, null);

		repository.markCompletedIfAllSettled(regId);

		RegistrationRow row = repository.findRecentByUser(userId, 10).get(0);
		assertThat(row.completedAt()).isNull();
	}

	@Test
	void markCompletedIfAllSettled_전부_정리되면_완료() {
		long regId = repository.insert(userId);
		repository.insertEntry(regId, 1, "a", "post", "pending", null, null, null, null);
		repository.insertEntry(regId, 2, "b", "post", "pending", null, null, null, null);

		repository.updateEntryResult(regId, 1, "success", null, null, null, null);
		repository.markCompletedIfAllSettled(regId);
		assertThat(repository.findRecentByUser(userId, 10).get(0).completedAt()).isNull();

		repository.updateEntryResult(regId, 2, "failed", "not_found", "찾을 수 없음", null, null);
		repository.markCompletedIfAllSettled(regId);
		assertThat(repository.findRecentByUser(userId, 10).get(0).completedAt()).isNotNull();
	}

	@Test
	void findRecentByUser_limit과_정렬() {
		long reg1 = repository.insert(userId);
		long reg2 = repository.insert(userId);
		long reg3 = repository.insert(userId);

		List<RegistrationRow> recent = repository.findRecentByUser(userId, 2);
		assertThat(recent).extracting(RegistrationRow::id).containsExactly(reg3, reg2);
	}

	@Test
	void countByUser() {
		assertThat(repository.countByUser(userId)).isZero();

		repository.insert(userId);
		repository.insert(userId);

		assertThat(repository.countByUser(userId)).isEqualTo(2);
	}
}
