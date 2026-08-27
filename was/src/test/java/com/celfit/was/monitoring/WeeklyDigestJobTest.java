package com.celfit.was.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.was.IntegrationTest;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import tools.jackson.databind.ObjectMapper;

/**
 * WeeklyDigestJob — 지난주(월~일 KST) 이벤트·발견분·미표기 판정을 모아 (user, 주 시작일)로
 * 멱등 upsert하는 주간 크론(설계 §2·§4·§8). MonitoringReadRepositoryTest·BrandReadRepositoryTest와
 * 같은 방식으로 monitoring 픽스처 2개를 같은 컨테이너의 public 스키마에 올려 monitoring DB를
 * 흉내 낸다. 잡은 Spring 조건부 배선과 무관하게 직접 new 한다.
 */
class WeeklyDigestJobTest extends IntegrationTest {

	/** 2026-08-24(월) 09:30 KST — 이 시각의 "지난주"는 2026-08-17(월) ~ 08-23(일)이다. */
	private static final Instant NOW = OffsetDateTime.of(2026, 8, 24, 9, 30, 0, 0, ZoneOffset.ofHours(9))
			.toInstant();
	private static final LocalDate WEEK_START = LocalDate.of(2026, 8, 17);
	private static final OffsetDateTime IN_WEEK =
			OffsetDateTime.of(2026, 8, 19, 12, 0, 0, 0, ZoneOffset.ofHours(9));
	private static final OffsetDateTime BEFORE_WEEK =
			OffsetDateTime.of(2026, 8, 16, 12, 0, 0, 0, ZoneOffset.ofHours(9));

	@Autowired
	DataSource dataSource;
	@Autowired
	DigestRepository digestRepository;
	@Autowired
	BrandLinkRepository brandLinkRepository;
	@Autowired
	BrandDirectPostRepository brandDirectPostRepository;
	@Autowired
	MonitoringItemRepository monitoringItemRepository;
	@Autowired
	AdDisclosureNoticeRepository adDisclosureNoticeRepository;
	@Autowired
	JdbcClient jdbcClient;

	JdbcClient monitoringJdbc;
	MonitoringReadRepository monitoringReadRepository;
	BrandReadRepository brandReadRepository;
	WeeklyDigestJob job;

	@BeforeEach
	void setUp() throws Exception {
		try (Connection conn = dataSource.getConnection()) {
			ScriptUtils.executeSqlScript(conn, new ClassPathResource("monitoring-schema.sql"));
			ScriptUtils.executeSqlScript(conn, new ClassPathResource("monitoring-brand-schema.sql"));
		}
		monitoringJdbc = JdbcClient.create(dataSource);
		monitoringJdbc.sql("TRUNCATE alarm_event RESTART IDENTITY").update();
		monitoringJdbc.sql("""
				TRUNCATE brand_tagged_post, brand_account, brand_post_meta, brand_post_snapshot,
				         brand_hashtag_post RESTART IDENTITY CASCADE
				""").update();
		monitoringReadRepository = new MonitoringReadRepository(monitoringJdbc);
		brandReadRepository = new BrandReadRepository(monitoringJdbc);
		job = newJob(true);
	}

	private WeeklyDigestJob newJob(boolean exposeAdDisclosure) {
		return new WeeklyDigestJob(monitoringReadRepository, brandReadRepository, brandLinkRepository,
				brandDirectPostRepository, monitoringItemRepository, adDisclosureNoticeRepository,
				digestRepository, new WeeklyDigestAssembler(), new ObjectMapper(),
				Clock.fixed(NOW, ZoneOffset.UTC), exposeAdDisclosure);
	}

	private long seedUser() {
		return jdbcClient.sql("INSERT INTO app.users (email, password_hash) VALUES (:email, 'x') RETURNING id")
				.param("email", "weekly-job-" + UUID.randomUUID() + "@test.io")
				.query(Long.class).single();
	}

	private void seedEvent(long targetId, long userId, String eventType, OffsetDateTime occurredAt) {
		monitoringJdbc.sql("""
				INSERT INTO alarm_event (target_id, user_id, event_type, payload, occurred_at, dispatch_after)
				VALUES (:targetId, :userId, :eventType, '{}'::jsonb, :occurredAt, :occurredAt)
				""")
				.param("targetId", targetId).param("userId", userId)
				.param("eventType", eventType).param("occurredAt", occurredAt)
				.update();
	}

	private long seedBrand(long userId, String username) {
		long brandId = monitoringJdbc.sql("""
				INSERT INTO brand_account (username, ig_user_id) VALUES (:username, '1') RETURNING id
				""").param("username", username).query(Long.class).single();
		brandLinkRepository.insertLink(userId, brandId, username, "own", 3);
		return brandId;
	}

	private void seedTagged(long brandId, String shortCode, OffsetDateTime tagDetectedAt) {
		monitoringJdbc.sql("""
				INSERT INTO brand_tagged_post (brand_id, short_code, author_username, taken_at, tag_detected_at)
				VALUES (:brandId, :shortCode, 'author_a', :takenAt, :tagDetectedAt)
				""")
				.param("brandId", brandId).param("shortCode", shortCode)
				.param("takenAt", IN_WEEK).param("tagDetectedAt", tagDetectedAt)
				.update();
	}

	private void seedMeta(String shortCode, String adVerdict, OffsetDateTime adJudgedAt) {
		monitoringJdbc.sql("""
				INSERT INTO brand_post_meta (short_code, username, uploaded_at, caption, ad_verdict, ad_judged_at)
				VALUES (:shortCode, 'author_a', DATE '2026-08-19', '캡션', :adVerdict, :adJudgedAt)
				""")
				.param("shortCode", shortCode).param("adVerdict", adVerdict).param("adJudgedAt", adJudgedAt)
				.update();
	}

	@SuppressWarnings("unchecked")
	private List<Map<String, Object>> items(long userId) {
		DigestRow row = digestRepository.findRecentByUser(userId, 30).stream()
				.filter(candidate -> candidate.digestDate().equals(WEEK_START))
				.findFirst()
				.orElseThrow(() -> new AssertionError("주간 다이제스트 없음 — user " + userId));
		return new ObjectMapper().readValue(row.itemsJson(), List.class);
	}

	@Test
	void 지난주_이벤트가_주_시작일_행_한_건으로_모인다() {
		long userId = seedUser();
		seedEvent(1, userId, "COLLECTION_STARTED", IN_WEEK);
		seedEvent(2, userId, "COLLECTION_STARTED", IN_WEEK);
		seedEvent(3, userId, "CONTENT_UNAVAILABLE", IN_WEEK);

		job.run();

		assertThat(digestRepository.countByUser(userId)).isEqualTo(1);
		assertThat(items(userId)).extracting(item -> item.get("type"))
				.containsExactly("content_issue", "collection_started");
		assertThat(items(userId).get(1)).containsEntry("count", 2);
	}

	@Test
	void 창_밖_이벤트는_집계되지_않는다() {
		long userId = seedUser();
		seedEvent(1, userId, "COLLECTION_STARTED", BEFORE_WEEK);

		job.run();

		assertThat(digestRepository.countByUser(userId)).isZero();
	}

	@Test
	void 이벤트도_소식도_없으면_다이제스트를_만들지_않는다() {
		long userId = seedUser();
		seedBrand(userId, "empty_brand");

		job.run();

		assertThat(digestRepository.countByUser(userId)).isZero();
	}

	@Test
	void 브랜드_발견분이_브랜드_소식으로_잡힌다() {
		long userId = seedUser();
		long brandId = seedBrand(userId, "found_brand");
		seedTagged(brandId, "SC_IN", IN_WEEK);
		seedTagged(brandId, "SC_OUT", BEFORE_WEEK);

		job.run();

		List<Map<String, Object>> items = items(userId);
		assertThat(items).hasSize(1);
		assertThat(items.get(0)).containsEntry("type", "brand_new_posts").containsEntry("count", 1);
	}

	@Test
	void 미표기_판정은_등록_게시물만_잡히고_이력에_남는다() {
		long userId = seedUser();
		long brandId = seedBrand(userId, "ad_brand");
		seedTagged(brandId, "SC_MINE", BEFORE_WEEK);      // 발견은 지난주 밖 - 브랜드 소식에는 안 잡힌다
		seedTagged(brandId, "SC_OTHERS", BEFORE_WEEK);
		seedMeta("SC_MINE", "NOT_DISCLOSED", IN_WEEK);
		seedMeta("SC_OTHERS", "NOT_DISCLOSED", IN_WEEK);
		brandDirectPostRepository.upsertDirect(userId, brandId, "SC_MINE");   // 등록 원장에는 SC_MINE만

		job.run();

		List<Map<String, Object>> items = items(userId);
		assertThat(items).hasSize(1);
		assertThat(items.get(0)).containsEntry("type", "ad_not_disclosed").containsEntry("count", 1);
		assertThat(adDisclosureNoticeRepository.findNotifiedInOtherWeek(userId, List.of("SC_MINE"),
				WEEK_START.plusWeeks(1))).containsExactly("SC_MINE");
	}

	@Test
	void 이미_알린_미표기_게시물은_다음_주에_다시_알리지_않는다() {
		long userId = seedUser();
		long brandId = seedBrand(userId, "reset_brand");
		seedTagged(brandId, "SC_MINE", BEFORE_WEEK);
		seedMeta("SC_MINE", "NOT_DISCLOSED", IN_WEEK);
		brandDirectPostRepository.upsertDirect(userId, brandId, "SC_MINE");
		adDisclosureNoticeRepository.markNotified(userId, List.of("SC_MINE"), WEEK_START.minusWeeks(1));

		job.run();

		assertThat(digestRepository.countByUser(userId)).isZero();
	}

	@Test
	void 킬_스위치가_꺼져_있으면_미표기_섹션을_만들지_않는다() {
		long userId = seedUser();
		long brandId = seedBrand(userId, "gated_brand");
		seedTagged(brandId, "SC_MINE", BEFORE_WEEK);
		seedMeta("SC_MINE", "NOT_DISCLOSED", IN_WEEK);
		brandDirectPostRepository.upsertDirect(userId, brandId, "SC_MINE");

		newJob(false).run();

		assertThat(digestRepository.countByUser(userId)).isZero();
	}

	@Test
	void 재실행해도_행이_늘지_않고_items만_갱신되며_읽음이_보존된다() {
		long userId = seedUser();
		seedEvent(1, userId, "COLLECTION_STARTED", IN_WEEK);
		job.run();
		long digestId = digestRepository.findRecentByUser(userId, 1).get(0).id();
		digestRepository.markRead(userId, List.of(digestId));
		DigestRow before = digestRepository.findRecentByUser(userId, 1).get(0);

		seedEvent(2, userId, "METRICS_HIDDEN", IN_WEEK);
		job.catchUp();

		DigestRow after = digestRepository.findRecentByUser(userId, 1).get(0);
		assertThat(digestRepository.countByUser(userId)).isEqualTo(1);
		assertThat(after.id()).isEqualTo(digestId);
		assertThat(after.readAt()).isEqualTo(before.readAt());
		assertThat(after.createdAt()).isEqualTo(before.createdAt());
		assertThat(items(userId)).extracting(item -> item.get("type"))
				.containsExactly("metrics_private", "collection_started");
	}

	@Test
	void 유저_2명이_각자_따로_집계된다() {
		long userA = seedUser();
		long userB = seedUser();
		seedEvent(1, userA, "COLLECTION_STARTED", IN_WEEK);
		seedEvent(2, userB, "COLLECTION_ENDED", IN_WEEK);

		job.run();

		assertThat(items(userA)).extracting(item -> item.get("type")).containsExactly("collection_started");
		assertThat(items(userB)).extracting(item -> item.get("type")).containsExactly("collection_ended");
	}

	@Test
	void 명시적_창을_주면_그_창만_집계한다() {
		long userId = seedUser();
		seedEvent(1, userId, "COLLECTION_STARTED", BEFORE_WEEK);   // 2026-08-16(일) = 그 전 주

		job.runFor(new WeekWindow(WEEK_START.minusWeeks(1)));

		assertThat(digestRepository.findRecentByUser(userId, 30))
				.extracting(DigestRow::digestDate)
				.containsExactly(WEEK_START.minusWeeks(1));
	}
}
