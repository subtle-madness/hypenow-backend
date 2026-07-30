package com.celfit.was.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.was.IntegrationTest;
import java.time.LocalDate;
import java.time.OffsetDateTime;
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
		userId = seedUser();
	}

	private long seedUser() {
		return jdbcClient.sql("INSERT INTO app.users (email, password_hash) VALUES (:email, 'x') RETURNING id")
				.param("email", "mon-reg-" + UUID.randomUUID() + "@test.io")
				.query(Long.class).single();
	}

	@Test
	void entry_seq_순서_보존() {
		long regId = repository.insert(userId, 14, null);
		repository.insertEntry(regId, 2, "second", "post", "pending", null, null, null, null);
		repository.insertEntry(regId, 1, "first", "post", "pending", null, null, null, null);
		repository.insertEntry(regId, 3, "third", "post", "pending", null, null, null, null);

		RegistrationRow row = repository.findRecentByUser(userId, 10).get(0);
		assertThat(row.entries()).extracting(RegistrationEntryRow::input).containsExactly("first", "second", "third");
	}

	@Test
	void nullable_필드는_null로_매핑된다() {
		long regId = repository.insert(userId, 14, null);
		repository.insertEntry(regId, 1, "pending-input", "post", "pending", null, null, null, null);

		RegistrationRow row = repository.findRecentByUser(userId, 10).get(0);
		assertThat(row.acknowledgedAt()).isNull();
		RegistrationEntryRow entry = row.entries().get(0);
		assertThat(entry.reasonCode()).isNull();
		assertThat(entry.reason()).isNull();
		assertThat(entry.resolvedUrl()).isNull();
		assertThat(entry.itemId()).isNull();
	}

	@Test
	void updateEntryResult_왕복() {
		long itemId = itemRepository.insertPending(userId, "url", UUID.randomUUID(), null, "abc123",
				"https://x/abc123", null, 30, LocalDate.of(2026, 7, 30));
		long regId = repository.insert(userId, 14, null);
		repository.insertEntry(regId, 1, "abc123", "post", "pending", null, null, null, null);

		repository.updateEntryResult(regId, 1, "success", null, null, "https://x/abc123", itemId);

		RegistrationEntryRow entry = repository.findRecentByUser(userId, 10).get(0).entries().get(0);
		assertThat(entry.result()).isEqualTo("success");
		assertThat(entry.resolvedUrl()).isEqualTo("https://x/abc123");
		assertThat(entry.itemId()).isEqualTo(itemId);
	}

	@Test
	void markCompletedIfAllSettled_pending_남으면_미완료() {
		long regId = repository.insert(userId, 14, null);
		repository.insertEntry(regId, 1, "a", "post", "success", null, null, null, null);
		repository.insertEntry(regId, 2, "b", "post", "pending", null, null, null, null);

		repository.markCompletedIfAllSettled(regId);

		RegistrationRow row = repository.findRecentByUser(userId, 10).get(0);
		assertThat(row.completedAt()).isNull();
	}

	@Test
	void markCompletedIfAllSettled_전부_정리되면_완료() {
		long regId = repository.insert(userId, 14, null);
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
		long reg1 = repository.insert(userId, 14, null);
		long reg2 = repository.insert(userId, 14, null);
		long reg3 = repository.insert(userId, 14, null);

		List<RegistrationRow> recent = repository.findRecentByUser(userId, 2);
		assertThat(recent).extracting(RegistrationRow::id).containsExactly(reg3, reg2);
	}

	@Test
	void findRecentByUser_51건_시드해도_50건_한도로_잘린다() {
		for (int i = 0; i < 51; i++) {
			repository.insert(userId, 14, null);
		}

		assertThat(repository.findRecentByUser(userId, 50)).hasSize(50);
		assertThat(repository.countByUser(userId)).isEqualTo(51);
	}

	@Test
	void countByUser() {
		assertThat(repository.countByUser(userId)).isZero();

		repository.insert(userId, 14, null);
		repository.insert(userId, 14, null);

		assertThat(repository.countByUser(userId)).isEqualTo(2);
	}

	@Test
	void trackingDays_campaignId_왕복() {
		long regId = repository.insert(userId, 21, null);

		RegistrationRow row = repository.findById(regId).orElseThrow();
		assertThat(row.trackingDays()).isEqualTo(21);
		assertThat(row.campaignId()).isNull();
	}

	@Test
	void findById_없는_id는_빈값() {
		assertThat(repository.findById(999_999L)).isEmpty();
	}

	@Test
	void findEntryByItemId_왕복() {
		long itemId = itemRepository.insertPending(userId, "url", UUID.randomUUID(), null, "abc123",
				"https://x/abc123", null, 30, LocalDate.of(2026, 7, 30));
		long regId = repository.insert(userId, 30, null);
		repository.insertEntry(regId, 1, "abc123", "post", "pending", null, null, null, itemId);

		RegistrationEntryRow entry = repository.findEntryByItemId(itemId).orElseThrow();
		assertThat(entry.registrationId()).isEqualTo(regId);
		assertThat(entry.seq()).isEqualTo(1);
	}

	@Test
	void findEntryByItemId_없는_item은_빈값() {
		assertThat(repository.findEntryByItemId(999_999L)).isEmpty();
	}

	@Test
	void markAcknowledged는_본인_소유_행만_처리한다() {
		long otherUserId = seedUser();
		long myRegId = repository.insert(userId, 14, null);
		long otherRegId = repository.insert(otherUserId, 14, null);

		// 타 유저 id·존재하지 않는 id를 섞어도 본인 것만 확인 처리된다(멱등, 404·403 없음).
		repository.markAcknowledged(userId, List.of(myRegId, otherRegId, 999_999L));

		assertThat(repository.findRecentByUser(userId, 10).get(0).acknowledgedAt()).isNotNull();
		assertThat(repository.findRecentByUser(otherUserId, 10).get(0).acknowledgedAt()).isNull();
	}

	@Test
	void markAcknowledged는_멱등이며_최초_확인_시각을_보존한다() throws InterruptedException {
		long regId = repository.insert(userId, 14, null);

		repository.markAcknowledged(userId, List.of(regId));
		OffsetDateTime firstAckAt = repository.findRecentByUser(userId, 10).get(0).acknowledgedAt();
		assertThat(firstAckAt).isNotNull();

		Thread.sleep(10); // now()가 다른 값을 찍을 여유를 준다 — 재적용돼도 안 바뀌어야 한다.
		repository.markAcknowledged(userId, List.of(regId));
		OffsetDateTime secondAckAt = repository.findRecentByUser(userId, 10).get(0).acknowledgedAt();

		assertThat(secondAckAt).isEqualTo(firstAckAt);
	}

	@Test
	void markAcknowledged_빈_리스트는_no_op() {
		long regId = repository.insert(userId, 14, null);

		repository.markAcknowledged(userId, List.of());

		assertThat(repository.findRecentByUser(userId, 10).get(0).acknowledgedAt()).isNull();
	}

	@Test
	void markAllAcknowledged는_응답_창_50건_밖의_행까지_전부_확인_처리한다() {
		for (int i = 0; i < 51; i++) {
			repository.insert(userId, 14, null);
		}
		assertThat(repository.countByUser(userId)).isEqualTo(51);

		repository.markAllAcknowledged(userId);

		long unacknowledgedCount = jdbcClient.sql(
				"SELECT count(*) FROM app.monitoring_registrations WHERE user_id = :userId AND acknowledged_at IS NULL")
				.param("userId", userId)
				.query(Long.class)
				.single();
		assertThat(unacknowledgedCount).isZero();
	}
}
