package com.celfit.was.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.celfit.was.IntegrationTest;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

class MonitoringItemRepositoryTest extends IntegrationTest {

	@Autowired
	MonitoringItemRepository repository;
	@Autowired
	CampaignRepository campaignRepository;
	@Autowired
	JdbcClient jdbcClient;
	private final ObjectMapper objectMapper = new ObjectMapper();

	long userId;

	@BeforeEach
	void 유저_시드() {
		userId = jdbcClient.sql("INSERT INTO app.users (email, password_hash) VALUES (:email, 'x') RETURNING id")
				.param("email", "mon-item-" + UUID.randomUUID() + "@test.io")
				.query(Long.class).single();
	}

	@Test
	void pending_선저장과_target_확정_왕복() {
		UUID key = UUID.randomUUID();
		long itemId = repository.insertPending(userId, "url", key, null, "abc123", "https://instagram.com/p/abc123",
				null, 30, LocalDate.of(2026, 7, 30));

		MonitoringItemRow before = repository.findByIdAndUser(itemId, userId).orElseThrow();
		assertThat(before.targetId()).isNull();
		assertThat(before.mode()).isEqualTo("url");
		assertThat(before.registrationKey()).isEqualTo(key);
		assertThat(before.trackingDays()).isEqualTo(30);

		repository.confirmTarget(itemId, 17L);

		MonitoringItemRow after = repository.findByIdAndUser(itemId, userId).orElseThrow();
		assertThat(after.targetId()).isEqualTo(17L);
	}

	@Test
	void confirmTarget_무효_id면_예외() {
		assertThatThrownBy(() -> repository.confirmTarget(999_999L, 1L))
				.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void findByUser_정렬은_등록일_ASC_동일하면_id_ASC() {
		LocalDate day = LocalDate.of(2026, 7, 30);
		long first = repository.insertPending(userId, "url", UUID.randomUUID(), null, "a", "https://x/a", null, 30, day);
		long second = repository.insertPending(userId, "url", UUID.randomUUID(), null, "b", "https://x/b", null, 30, day);
		long earlier = repository.insertPending(userId, "url", UUID.randomUUID(), null, "c", "https://x/c", null, 30,
				day.minusDays(1));

		List<MonitoringItemRow> byUser = repository.findByUser(userId);
		assertThat(byUser).extracting(MonitoringItemRow::id).containsExactly(earlier, first, second);
	}

	@Test
	void findActiveByInput은_취소된_행을_제외한다() {
		String inputValue = "shared-handle";
		long active = repository.insertPending(userId, "account", UUID.randomUUID(), null, inputValue, null,
				"""
				{"and":[],"or":[],"exclude":[]}
				""", 30, LocalDate.of(2026, 7, 30));
		long canceled = repository.insertPending(userId, "account", UUID.randomUUID(), null, inputValue, null,
				null, 30, LocalDate.of(2026, 7, 30));
		repository.markCanceled(canceled, "tracking", OffsetDateTime.now());

		List<MonitoringItemRow> activeOnes = repository.findActiveByInput(userId, "account", inputValue);
		assertThat(activeOnes).extracting(MonitoringItemRow::id).containsExactly(active);
	}

	@Test
	void keywords_jsonb_왕복() {
		// jsonb는 저장 시 키 순서·공백을 보존하지 않는다(Postgres jsonb 정규화) — 구조 비교로 검증
		String json = """
				{"and":["A"],"or":["B","C"],"exclude":["D"]}""";
		long itemId = repository.insertPending(userId, "account", UUID.randomUUID(), null, "handle", null, json, 30,
				LocalDate.of(2026, 7, 30));

		MonitoringItemRow row = repository.findByIdAndUser(itemId, userId).orElseThrow();
		Map<?, ?> roundTripped = objectMapper.readValue(row.keywords(), Map.class);
		assertThat(roundTripped).isEqualTo(objectMapper.readValue(json, Map.class));
	}

	@Test
	void markCanceled_후_필드_확인() {
		long itemId = repository.insertPending(userId, "url", UUID.randomUUID(), null, "abc", "https://x/abc", null,
				30, LocalDate.of(2026, 7, 30));
		OffsetDateTime at = OffsetDateTime.now();

		repository.markCanceled(itemId, "detecting", at);

		MonitoringItemRow row = repository.findByIdAndUser(itemId, userId).orElseThrow();
		assertThat(row.canceledFrom()).isEqualTo("detecting");
		assertThat(row.canceledAt()).isCloseTo(at, within3Seconds());
	}

	@Test
	void updateTrackingDays_updateCampaign_왕복() {
		long itemId = repository.insertPending(userId, "url", UUID.randomUUID(), null, "abc", "https://x/abc", null,
				30, LocalDate.of(2026, 7, 30));

		repository.updateTrackingDays(itemId, 60);
		assertThat(repository.findByIdAndUser(itemId, userId).orElseThrow().trackingDays()).isEqualTo(60);

		CampaignRow campaign = campaignRepository.insert(userId, "배정캠페인", null, null, null, null, null);
		repository.updateCampaign(itemId, campaign.id());
		assertThat(repository.findByIdAndUser(itemId, userId).orElseThrow().campaignId()).isEqualTo(campaign.id());

		repository.updateCampaign(itemId, null);
		assertThat(repository.findByIdAndUser(itemId, userId).orElseThrow().campaignId()).isNull();
	}

	@Test
	void markStartedNotified_빈리스트는_noop() {
		repository.markStartedNotified(List.of(), LocalDate.now());
		// 예외 없이 끝나면 성공
	}

	@Test
	void markStartedNotified_필드_확인() {
		long itemId = repository.insertPending(userId, "url", UUID.randomUUID(), null, "abc", "https://x/abc", null,
				30, LocalDate.of(2026, 7, 30));
		LocalDate on = LocalDate.of(2026, 7, 31);

		repository.markStartedNotified(List.of(itemId), on);

		assertThat(repository.findByIdAndUser(itemId, userId).orElseThrow().startedNotifiedOn()).isEqualTo(on);
	}

	@Test
	void findPendingOlderThan_경계_방금_만든_행은_미포함() {
		repository.insertPending(userId, "url", UUID.randomUUID(), null, "fresh", "https://x/fresh", null, 30,
				LocalDate.of(2026, 7, 30));

		List<MonitoringItemRow> oldPending = repository.findPendingOlderThan(Duration.ofHours(1));

		assertThat(oldPending).noneMatch(row -> row.inputValue().equals("fresh"));
	}

	@Test
	void findPendingOlderThan_target_확정된_행은_제외() {
		long itemId = repository.insertPending(userId, "url", UUID.randomUUID(), null, "confirmed", "https://x/c",
				null, 30, LocalDate.of(2026, 7, 30));
		repository.confirmTarget(itemId, 1L);

		// created_at 은 과거로 보이지 않으니 age=0 으로도 confirmed 행은 애초에 target_id IS NULL 조건에서 제외돼야 한다
		List<MonitoringItemRow> oldPending = repository.findPendingOlderThan(Duration.ZERO);
		assertThat(oldPending).noneMatch(row -> row.id() == itemId);
	}

	private static org.assertj.core.data.TemporalUnitWithinOffset within3Seconds() {
		return new org.assertj.core.data.TemporalUnitWithinOffset(3, java.time.temporal.ChronoUnit.SECONDS);
	}
}
