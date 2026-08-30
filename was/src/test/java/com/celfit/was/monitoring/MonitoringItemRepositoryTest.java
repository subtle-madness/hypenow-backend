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

	private CampaignRow 캠페인() {
		return campaignRepository.insert(userId, "캠페인-" + UUID.randomUUID(), null, null, null, null, null, null);
	}
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
	void delete_후_조회되지_않는다() {
		long itemId = repository.insertPending(userId, "url", UUID.randomUUID(), null, "abc123",
				"https://instagram.com/p/abc123", null, 30, LocalDate.of(2026, 7, 30));
		assertThat(repository.findByIdAndUser(itemId, userId)).isPresent();

		repository.delete(itemId);

		assertThat(repository.findByIdAndUser(itemId, userId)).isEmpty();
	}

	@Test
	void 등록_롤백으로_item을_지우면_아카이브에_남는다() {
		long itemId = repository.insertPending(userId, "url", UUID.randomUUID(), null, "abc123",
				"https://instagram.com/p/abc123", null, 30, LocalDate.of(2026, 7, 30));

		repository.delete(itemId);

		String reason = jdbcClient.sql("""
						SELECT archived_reason FROM archive.archived_rows
						 WHERE table_name = 'app.monitoring_items' AND user_id = :id
						""")
				.param("id", userId)
				.query(String.class)
				.single();

		assertThat(reason).isEqualTo("REGISTRATION_ROLLBACK");
		assertThat(jdbcClient.sql("SELECT count(*) FROM app.monitoring_items WHERE id = :id")
				.param("id", itemId)
				.query(Long.class)
				.single()).isZero();
	}

	/**
	 * brand_direct_posts.monitoring_item_id는 더 이상 CASCADE가 아니다(V20260811090500) — 남겨두면
	 * item DELETE가 FK 위반으로 실패한다. delete가 이 매핑도 먼저 아카이브·삭제하는지 확인한다.
	 */
	@Test
	void 등록_롤백으로_item을_지우면_연결된_brand_direct_posts_매핑도_함께_아카이브된다() {
		long itemId = repository.insertPending(userId, "url", UUID.randomUUID(), null, "abc123",
				"https://instagram.com/p/abc123", null, 30, LocalDate.of(2026, 7, 30));
		jdbcClient.sql("""
						INSERT INTO app.brand_direct_posts (user_id, brand_id, short_code, monitoring_item_id)
						VALUES (:userId, 999, 'abc123', :itemId)
						""")
				.param("userId", userId)
				.param("itemId", itemId)
				.update();

		repository.delete(itemId);

		String reason = jdbcClient.sql("""
						SELECT archived_reason FROM archive.archived_rows
						 WHERE table_name = 'app.brand_direct_posts' AND user_id = :id
						""")
				.param("id", userId)
				.query(String.class)
				.single();

		assertThat(reason).isEqualTo("REGISTRATION_ROLLBACK");
		assertThat(jdbcClient.sql("SELECT count(*) FROM app.brand_direct_posts WHERE user_id = :id")
				.param("id", userId)
				.query(Long.class)
				.single()).isZero();
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

		CampaignRow campaign = campaignRepository.insert(userId, "배정캠페인", null, null, null, null, null, null);
		repository.updateCampaign(itemId, campaign.id());
		assertThat(repository.findByIdAndUser(itemId, userId).orElseThrow().campaignId()).isEqualTo(campaign.id());

		repository.updateCampaign(itemId, null);
		assertThat(repository.findByIdAndUser(itemId, userId).orElseThrow().campaignId()).isNull();
	}

	@Test
	void findPendingOlderThan_경계_방금_만든_행은_미포함() {
		repository.insertPending(userId, "url", UUID.randomUUID(), null, "fresh", "https://x/fresh", null, 30,
				LocalDate.of(2026, 7, 30));

		List<MonitoringItemRow> oldPending = repository.findPendingOlderThan(Duration.ofHours(1));

		assertThat(oldPending).noneMatch(row -> row.inputValue().equals("fresh"));
	}

	@Test
	void findPendingOlderThan_age_넘은_pending_행은_포함() {
		long itemId = repository.insertPending(userId, "url", UUID.randomUUID(), null, "stale", "https://x/stale",
				null, 30, LocalDate.of(2026, 7, 30));
		jdbcClient.sql("UPDATE app.monitoring_items SET created_at = now() - interval '2 hours' WHERE id = :id")
				.param("id", itemId)
				.update();

		List<MonitoringItemRow> oldPending = repository.findPendingOlderThan(Duration.ofHours(1));

		assertThat(oldPending).anyMatch(row -> row.id() == itemId);
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

	// ---------- findCampaignLinkedAccountHandles(seededAuthor 캠페인 도출, 2026-08-18) ----------

	@Test
	void findCampaignLinkedAccountHandles_캠페인_연결된_계정_추적만_반환한다() {
		CampaignRow campaign = 캠페인();
		repository.insertPending(userId, "account", UUID.randomUUID(), campaign.id(), "seeded_creator", null,
				null, 30, LocalDate.of(2026, 8, 1));

		List<String> handles = repository.findCampaignLinkedAccountHandles(userId);

		assertThat(handles).containsExactly("seeded_creator");
	}

	@Test
	void findCampaignLinkedAccountHandles_캠페인_없는_계정_추적은_제외() {
		repository.insertPending(userId, "account", UUID.randomUUID(), null, "no_campaign_creator", null,
				null, 30, LocalDate.of(2026, 8, 1));

		assertThat(repository.findCampaignLinkedAccountHandles(userId)).isEmpty();
	}

	@Test
	void findCampaignLinkedAccountHandles_취소된_추적은_제외() {
		CampaignRow campaign = 캠페인();
		long itemId = repository.insertPending(userId, "account", UUID.randomUUID(), campaign.id(),
				"canceled_creator", null, null, 30, LocalDate.of(2026, 8, 1));
		repository.markCanceled(itemId, "detecting", OffsetDateTime.now());

		assertThat(repository.findCampaignLinkedAccountHandles(userId)).isEmpty();
	}

	@Test
	void findCampaignLinkedAccountHandles_url_모드는_제외() {
		CampaignRow campaign = 캠페인();
		repository.insertPending(userId, "url", UUID.randomUUID(), campaign.id(), "abc123",
				"https://instagram.com/p/abc123", null, 30, LocalDate.of(2026, 8, 1));

		assertThat(repository.findCampaignLinkedAccountHandles(userId)).isEmpty();
	}

	// ---------- findCampaignNamesByTargetIds(캠페인 이름 문맥 조회, 2026-08-27 주간 다이제스트) ----------

	/** 캠페인 이름 문맥 조회 전용 헬퍼 — 아래 테스트 3개에서만 쓴다. */
	private long 이름있는_캠페인(long ownerId, String name) {
		return campaignRepository.insert(ownerId, name, null, null, null, null, null, null).id();
	}

	private void 추적행_시드(long ownerId, Long campaignId, long targetId, String inputValue) {
		long itemId = repository.insertPending(ownerId, "url", UUID.randomUUID(), campaignId, inputValue,
				"https://instagram.com/p/" + inputValue, null, 30, LocalDate.of(2026, 8, 19));
		repository.confirmTarget(itemId, targetId);
	}

	@Test
	void 캠페인_이름은_이름순_중복_제거로_돌아온다() {
		long summer = 이름있는_캠페인(userId, "여름 캠페인");
		long winter = 이름있는_캠페인(userId, "겨울 캠페인");
		추적행_시드(userId, summer, 8001L, "camp01");
		추적행_시드(userId, summer, 8002L, "camp02");
		추적행_시드(userId, winter, 8003L, "camp03");

		assertThat(repository.findCampaignNamesByTargetIds(userId, List.of(8001L, 8002L, 8003L)))
				.containsExactly("겨울 캠페인", "여름 캠페인");
	}

	@Test
	void 캠페인이_없는_추적_행은_이름을_만들지_않는다() {
		추적행_시드(userId, null, 8010L, "camp10");

		assertThat(repository.findCampaignNamesByTargetIds(userId, List.of(8010L))).isEmpty();
	}

	@Test
	void 빈_target_목록은_조회하지_않고_빈_리스트() {
		assertThat(repository.findCampaignNamesByTargetIds(userId, List.of())).isEmpty();
	}

	@Test
	void 남의_추적_행은_캠페인_이름을_노출하지_않는다() {
		long other = jdbcClient
				.sql("INSERT INTO app.users (email, password_hash) VALUES (:email, 'x') RETURNING id")
				.param("email", "mon-item-other-" + UUID.randomUUID() + "@test.io")
				.query(Long.class).single();
		long campaignId = 이름있는_캠페인(userId, "남의 캠페인");
		추적행_시드(userId, campaignId, 8020L, "camp20");

		assertThat(repository.findCampaignNamesByTargetIds(other, List.of(8020L))).isEmpty();
	}
}
