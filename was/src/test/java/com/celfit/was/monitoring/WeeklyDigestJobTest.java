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
		monitoringJdbc.sql("TRUNCATE alarm_event, target, post_snapshot RESTART IDENTITY").update();
		monitoringJdbc.sql("""
				TRUNCATE brand_tagged_post, brand_account, brand_post_meta, brand_post_snapshot,
				         brand_hashtag_post RESTART IDENTITY CASCADE
				""").update();
		monitoringReadRepository = new MonitoringReadRepository(monitoringJdbc);
		brandReadRepository = new BrandReadRepository(monitoringJdbc);
		job = newJob(true);
	}

	private WeeklyDigestJob newJob(boolean exposeAdDisclosure) {
		return newJobAt(exposeAdDisclosure, NOW);
	}

	private WeeklyDigestJob newJobAt(boolean exposeAdDisclosure, Instant now) {
		return new WeeklyDigestJob(monitoringReadRepository, brandReadRepository, brandLinkRepository,
				brandDirectPostRepository, monitoringItemRepository, adDisclosureNoticeRepository,
				digestRepository, new WeeklyDigestAssembler(), new ObjectMapper(),
				Clock.fixed(now, ZoneOffset.UTC), exposeAdDisclosure);
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

	/** 레거시 캠페인 추적(target) 1행 — endedPosts() 실경로 검증용(I6-2). RETURNING id로 alarm_event.target_id에 쓴다. */
	private long seedTarget(String username, String trackedShortCode) {
		return monitoringJdbc.sql("""
				INSERT INTO target (type, username, status, tracked_short_code, tracked_since,
				                    registration_key, expires_at)
				VALUES ('POST', :username, 'TRACKING', :trackedShortCode, :trackedSince,
				        :registrationKey, :expiresAt)
				RETURNING id
				""")
				.param("username", username).param("trackedShortCode", trackedShortCode)
				.param("trackedSince", IN_WEEK).param("registrationKey", "rk-" + UUID.randomUUID())
				.param("expiresAt", IN_WEEK.plusDays(30))
				.query(Long.class).single();
	}

	private void seedPostSnapshot(String shortCode, LocalDate capturedOn, String contentType,
			Long views, Long likes, Long comments) {
		monitoringJdbc.sql("""
				INSERT INTO post_snapshot (username, short_code, captured_on, content_type, likes, comments, views)
				VALUES ('author_e', :shortCode, :capturedOn, :contentType, :likes, :comments, :views)
				""")
				.param("shortCode", shortCode).param("capturedOn", capturedOn).param("contentType", contentType)
				.param("likes", likes).param("comments", comments).param("views", views)
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

	@Test
	void 화요일에_따라잡기가_돌아도_같은_주간_창을_복구한다() {
		// 품질 리뷰 C1 — 따라잡기 기본 크론이 월요일 한정이 아니라 매일이어야 한다. 화요일
		// 10:00 KST에서 WeekWindow.previousWeekOf는 여전히 같은 WEEK_START(2026-08-17)를 준다.
		long userId = seedUser();
		seedEvent(1, userId, "COLLECTION_STARTED", IN_WEEK);
		Instant tuesday = OffsetDateTime.of(2026, 8, 25, 10, 0, 0, 0, ZoneOffset.ofHours(9)).toInstant();

		newJobAt(true, tuesday).catchUp();

		assertThat(digestRepository.countByUser(userId)).isEqualTo(1);
		assertThat(items(userId)).extracting(item -> item.get("type")).containsExactly("collection_started");
	}

	@Test
	void 킬_스위치를_끄고_재실행하면_목록에서_사라지지만_행_자체는_보존된다() {
		// 품질 리뷰 I4(재리뷰 Important로 delete→clearItems 교체) — 행을 통째로 지우면
		// email_sent_at·email_attempts까지 함께 사라져 "발송됨 → 삭제 → 복구 → 재생성" 경로에서
		// 중복 발송·읽음 부활이 생긴다. clearItems는 items만 비우므로 행 자체(countByUser)는
		// 그대로 남고, FE 노출(countVisibleByUser)만 사라져야 한다.
		long userId = seedUser();
		long brandId = seedBrand(userId, "toggle_brand");
		seedTagged(brandId, "SC_MINE", BEFORE_WEEK);
		seedMeta("SC_MINE", "NOT_DISCLOSED", IN_WEEK);
		brandDirectPostRepository.upsertDirect(userId, brandId, "SC_MINE");
		job.run();   // 킬 스위치 on(기본 job) — 미표기 항목만 있는 행 생성
		assertThat(digestRepository.countVisibleByUser(userId)).isEqualTo(1);

		newJob(false).run();   // 킬 스위치 off로 재실행 — 조립 결과가 빈 목록이 된다

		assertThat(digestRepository.countByUser(userId)).isEqualTo(1);        // 행 자체는 보존
		assertThat(digestRepository.countVisibleByUser(userId)).isZero();     // 목록·total에서는 제외
		assertThat(digestRepository.findVisibleRecentByUser(userId, 30)).isEmpty();
	}

	@Test
	void 클리어_후_같은_주에_재채워지면_email_sent_at이_보존된다() {
		// 품질 리뷰 재리뷰 Important ② — clear(킬 스위치 off) 다음 같은 주 안에서 다시 채워지면
		// (킬 스위치 재활성화 등) 이미 발송됐던 메일 기록이 살아 있어야 중복 발송을 피할 수 있다.
		// 발송 자체(Task 10)는 아직 구현 전이라 "이미 보냈다"는 상태를 SQL로 직접 흉내낸다.
		long userId = seedUser();
		long brandId = seedBrand(userId, "resend_brand");
		seedTagged(brandId, "SC_MINE", BEFORE_WEEK);
		seedMeta("SC_MINE", "NOT_DISCLOSED", IN_WEEK);
		brandDirectPostRepository.upsertDirect(userId, brandId, "SC_MINE");
		job.run();
		long digestId = digestRepository.findRecentByUser(userId, 1).get(0).id();
		jdbcClient.sql("UPDATE app.monitoring_digests SET email_sent_at = now() WHERE id = :id")
				.param("id", digestId)
				.update();

		newJob(false).run();   // 킬 스위치 off — clearItems로 items만 비워짐
		assertThat(digestRepository.countVisibleByUser(userId)).isZero();

		job.run();   // 킬 스위치 다시 on — 같은 주라 upsertWeekly가 같은 행을 재채운다

		assertThat(digestRepository.countByUser(userId)).isEqualTo(1);   // 새 행이 아니라 같은 행
		assertThat(digestRepository.findRecentByUser(userId, 1).get(0).id()).isEqualTo(digestId);
		OffsetDateTime emailSentAt = jdbcClient.sql(
				"SELECT email_sent_at FROM app.monitoring_digests WHERE id = :id")
				.param("id", digestId)
				.query(OffsetDateTime.class).single();
		assertThat(emailSentAt).isNotNull();   // clear·재upsert 어느 쪽도 email_sent_at을 건드리지 않는다
	}

	@Test
	void 한_유저가_브랜드_2개를_걸어도_같은_shortCode_발견분은_한_번만_잡힌다() {
		// 품질 리뷰 재리뷰 Nit 3 — 같은 게시물이 두 브랜드 모두에 태그로 잡히면(brand_tagged_post PK는
		// (brand_id, short_code)라 구조적으로 가능하다) 배치 조회(I3)가 브랜드별로 별도 행을 돌려주므로,
		// 유저 내부 dedup(shortCode 기준)이 없으면 count가 이중 계상된다.
		long userId = seedUser();
		long brandA = seedBrand(userId, "brand_a");
		long brandB = seedBrand(userId, "brand_b");
		seedTagged(brandA, "SC_SHARED", IN_WEEK);
		seedTagged(brandB, "SC_SHARED", IN_WEEK);

		job.run();

		List<Map<String, Object>> items = items(userId);
		assertThat(items).hasSize(1);
		assertThat(items.get(0)).containsEntry("type", "brand_new_posts").containsEntry("count", 1);
	}

	@Test
	void 일요일_23시59분_이벤트는_포함되고_다음_월요일_00시_이벤트는_배제된다() {
		// 품질 리뷰 I6-1 — 창 상한 경계 정밀도. endDateInclusive(일요일)의 마지막 분은 포함,
		// 다음 주 창의 첫 순간(월요일 00:00:00 정각)은 배제(occurred_at < to는 엄격 부등호).
		long userId = seedUser();
		OffsetDateTime sundayLate = OffsetDateTime.of(2026, 8, 23, 23, 59, 0, 0, ZoneOffset.ofHours(9));
		OffsetDateTime nextMondayMidnight = OffsetDateTime.of(2026, 8, 24, 0, 0, 0, 0, ZoneOffset.ofHours(9));
		seedEvent(1, userId, "COLLECTION_STARTED", sundayLate);
		seedEvent(2, userId, "COLLECTION_ENDED", nextMondayMidnight);

		job.run();

		assertThat(items(userId)).extracting(item -> item.get("type")).containsExactly("collection_started");
	}

	@Test
	void 일요일_심야_도착_이벤트가_월요일_정시_실행에_흡수된다() {
		// 품질 리뷰 I6-3 — 따라잡기가 아니라 월요일 09:00 정시 run() 경로에서도 일요일 심야
		// 이벤트가 그 주 다이제스트로 정상 흡수돼야 한다(자정 경계 유실류 사고의 재발 방지).
		long userId = seedUser();
		OffsetDateTime sundayLateNight = OffsetDateTime.of(2026, 8, 23, 23, 58, 0, 0, ZoneOffset.ofHours(9));
		seedEvent(1, userId, "CONTENT_UNAVAILABLE", sundayLateNight);

		job.run();

		assertThat(digestRepository.countByUser(userId)).isEqualTo(1);
		assertThat(items(userId)).extracting(item -> item.get("type")).containsExactly("content_issue");
	}

	@Test
	void 수집_종료_실경로는_최신_스냅샷_지표로_항목과_하이라이트를_만든다() {
		// 품질 리뷰 I6-2 — endedPosts()가 target.tracked_short_code를 되짚어 post_snapshot 최신
		// 1행을 찾아오는 전체 경로를 target·post_snapshot 실테이블 시드로 검증한다(Task 8은
		// findTargets·findLatestSnapshots를 잡 레벨에서 이 경로로 검증한 적이 없었다).
		long userId = seedUser();
		long targetId = seedTarget("author_e", "SC_ENDED");
		seedPostSnapshot("SC_ENDED", LocalDate.of(2026, 8, 18), "REELS", 100L, 10L, 2L);
		seedPostSnapshot("SC_ENDED", LocalDate.of(2026, 8, 20), "REELS", 500L, 50L, 5L);   // 최신 스냅샷
		seedEvent(targetId, userId, "COLLECTION_ENDED", IN_WEEK);

		job.run();

		List<Map<String, Object>> items = items(userId);
		assertThat(items).extracting(item -> item.get("type")).containsExactly("collection_ended", "top_post");

		Map<String, Object> ended = items.get(0);
		assertThat(ended).containsEntry("count", 1);
		@SuppressWarnings("unchecked")
		Map<String, Object> endedMetrics = (Map<String, Object>) ended.get("metrics");
		assertThat(endedMetrics).containsEntry("views", 500).containsEntry("likes", 50).containsEntry("comments", 5);

		Map<String, Object> highlight = items.get(1);
		assertThat(highlight.get("summary")).isEqualTo("@author_e 게시물 · 조회수 500");
	}
}
