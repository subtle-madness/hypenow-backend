package com.celfit.was.v1.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.was.IntegrationTest;
import com.celfit.was.monitoring.CampaignRepository;
import com.celfit.was.monitoring.CampaignRow;
import com.celfit.was.monitoring.MonitoringItemRepository;
import com.celfit.was.monitoring.MonitoringReadRepository;
import com.celfit.was.monitoring.PostCommentRow;
import com.celfit.was.monitoring.TrackedSnapshotRow;
import java.sql.Connection;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
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
 * TrackingItemAssembler 통합 테스트(6.26) — monitoring-schema.sql을 같은 컨테이너의 public
 * 스키마에 적용해 실 target·post_meta·post_snapshot·post_comment·profile_meta·sweep_run 조회를
 * 태운다(V1MonitoringItemUpdateIntegrationTest와 동일 관례).
 */
class TrackingItemAssemblerTest extends IntegrationTest {

	@Autowired
	MonitoringItemRepository itemRepository;
	@Autowired
	CampaignRepository campaignRepository;
	@Autowired
	DataSource dataSource;
	@Autowired
	JdbcClient jdbcClient;

	JdbcClient monitoringJdbc;
	TrackingItemAssembler assembler;
	long userId;

	@BeforeEach
	void setUp() throws Exception {
		try (Connection conn = dataSource.getConnection()) {
			ScriptUtils.executeSqlScript(conn, new ClassPathResource("monitoring-schema.sql"));
		}
		monitoringJdbc = JdbcClient.create(dataSource);
		monitoringJdbc.sql("""
				TRUNCATE target, detected_candidate, profile_snapshot, post_snapshot,
				         post_meta, post_comment, profile_meta, sweep_run RESTART IDENTITY
				""")
				.update();

		MonitoringReadRepository readRepository = new MonitoringReadRepository(monitoringJdbc);
		assembler = new TrackingItemAssembler(itemRepository, campaignRepository, Optional.of(readRepository),
				new ObjectMapper());

		userId = jdbcClient.sql("INSERT INTO app.users (email, password_hash) VALUES (:email, 'x') RETURNING id")
				.param("email", "mon-assembler-" + UUID.randomUUID() + "@test.io")
				.query(Long.class).single();
	}

	// ── 시드 헬퍼 ────────────────────────────────────────────────────────────

	private long seedTarget(String type, String username, String status, String trackedShortCode,
			OffsetDateTime trackedSince, OffsetDateTime trackedHiddenAt, boolean fetchFailing, String matchedKeywordsJson) {
		return monitoringJdbc.sql("""
				INSERT INTO target (type, username, status, tracked_short_code, tracked_since, registration_key,
				                    expires_at, tracked_hidden_at, fetch_failing, matched_keywords)
				VALUES (:type, :username, :status, :tracked, :trackedSince, gen_random_uuid()::text,
				        now() + interval '30 days', :hiddenAt, :fetchFailing, CAST(:matchedKeywords AS jsonb))
				RETURNING id
				""")
				.param("type", type).param("username", username).param("status", status)
				.param("tracked", trackedShortCode).param("trackedSince", trackedSince).param("hiddenAt", trackedHiddenAt)
				.param("fetchFailing", fetchFailing).param("matchedKeywords", matchedKeywordsJson)
				.query(Long.class).single();
	}

	private void seedPostMeta(String shortCode, String username, String contentType, LocalDate uploadedAt,
			String caption, String thumbnailUrl) {
		seedPostMeta(shortCode, username, contentType, uploadedAt, caption, thumbnailUrl, null);
	}

	/** imageObjectPath가 있으면 monitoring 자체 썸네일 아카이브(트랙 KK 확장) 결과가 채워진 행을 흉내낸다. */
	private void seedPostMeta(String shortCode, String username, String contentType, LocalDate uploadedAt,
			String caption, String thumbnailUrl, String imageObjectPath) {
		monitoringJdbc.sql("""
				INSERT INTO post_meta (short_code, username, content_type, uploaded_at, caption, thumbnail_url, image_object_path)
				VALUES (:shortCode, :username, :contentType, :uploadedAt, :caption, :thumbnailUrl, :imageObjectPath)
				""")
				.param("shortCode", shortCode).param("username", username).param("contentType", contentType)
				.param("uploadedAt", uploadedAt).param("caption", caption).param("thumbnailUrl", thumbnailUrl)
				.param("imageObjectPath", imageObjectPath)
				.update();
	}

	private void seedSnapshot(String shortCode, String username, LocalDate capturedOn, String contentType,
			Long likes, Long views, Long shares) {
		monitoringJdbc.sql("""
				INSERT INTO post_snapshot (username, short_code, captured_on, content_type, likes, comments,
				                           views, saves, shares, reposts)
				VALUES (:username, :shortCode, :capturedOn, :contentType, :likes, 1, :views, 2, :shares, 0)
				""")
				.param("username", username).param("shortCode", shortCode).param("capturedOn", capturedOn)
				.param("contentType", contentType).param("likes", likes).param("views", views).param("shares", shares)
				.update();
	}

	private void seedComment(String shortCode, String id, String author, String body, long likeCount,
			OffsetDateTime commentedAt, String ownerReplyText) {
		monitoringJdbc.sql("""
				INSERT INTO post_comment (short_code, id, author, body, like_count, commented_at, owner_reply_text)
				VALUES (:shortCode, :id, :author, :body, :likeCount, :commentedAt, :ownerReplyText)
				""")
				.param("shortCode", shortCode).param("id", id).param("author", author).param("body", body)
				.param("likeCount", likeCount).param("commentedAt", commentedAt).param("ownerReplyText", ownerReplyText)
				.update();
	}

	private void seedProfileMeta(String username, String displayName, String profileImageUrl) {
		seedProfileMeta(username, displayName, profileImageUrl, null);
	}

	/** imageObjectPath가 있으면 monitoring 자체 아카이브(설계 스펙 §3-1) 결과가 채워진 행을 흉내낸다. */
	private void seedProfileMeta(String username, String displayName, String profileImageUrl, String imageObjectPath) {
		monitoringJdbc.sql("""
				INSERT INTO profile_meta (username, display_name, profile_image_url, updated_at, image_object_path)
				VALUES (:username, :displayName, :profileImageUrl, now(), :imageObjectPath)
				""")
				.param("username", username).param("displayName", displayName)
				.param("profileImageUrl", profileImageUrl).param("imageObjectPath", imageObjectPath)
				.update();
	}

	private void seedSuccessfulSweep(LocalDate completedOn) {
		monitoringJdbc.sql("""
				INSERT INTO sweep_run (started_at, completed_at, ok)
				VALUES (:completedAt, :completedAt, true)
				""")
				.param("completedAt", completedOn.atTime(2, 30).atZone(java.time.ZoneId.of("Asia/Seoul")).toOffsetDateTime())
				.update();
	}

	private long seedUrlItem(Long campaignId, LocalDate registeredOn, String shortCode, String sourceUrl) {
		return itemRepository.insertPending(userId, "url", UUID.randomUUID(), campaignId, shortCode, sourceUrl, null,
				14, registeredOn);
	}

	private long seedAccountItem(Long campaignId, LocalDate registeredOn, String handle) {
		return itemRepository.insertPending(userId, "account", UUID.randomUUID(), campaignId, handle, null,
				"{\"and\":[\"A\"],\"or\":[],\"exclude\":[]}", 14, registeredOn);
	}

	// ── 시나리오 ─────────────────────────────────────────────────────────────

	@Test
	void url_tracking_행은_post가_완전_조립된다() {
		LocalDate registeredOn = LocalDate.now().minusDays(5);
		long targetId = seedTarget("POST", "glowdeep", "TRACKING", "SHORT1", null, null, false, null);
		long itemId = seedUrlItem(null, registeredOn, "SHORT1", "https://www.instagram.com/p/SHORT1/");
		itemRepository.confirmTarget(itemId, targetId);

		seedPostMeta("SHORT1", "glowdeep", "REELS", registeredOn, "캡션 원문", "http://cdn/thumb.jpg");
		seedSnapshot("SHORT1", "glowdeep", registeredOn.plusDays(1), "REELS", 100L, 500L, 10L);
		seedSuccessfulSweep(LocalDate.now());
		seedComment("SHORT1", "c1", "author_ab", "댓글1", 5, OffsetDateTime.now(), "@author_ab 작성자 답글");

		TrackingItemAssembler.AssembledList assembled = assembler.assembleList(userId);

		assertThat(assembled.items()).hasSize(1);
		TrackingItemResponse item = assembled.items().get(0);
		assertThat(item.status()).isEqualTo("tracking");
		assertThat(item.handle()).isEqualTo("glowdeep");
		assertThat(item.post()).isNotNull();
		assertThat(item.post().url()).isEqualTo("https://www.instagram.com/p/SHORT1/");
		assertThat(item.post().contentType()).isEqualTo("reels");
		assertThat(item.post().caption()).isEqualTo("캡션 원문");
		assertThat(item.post().matchedKeywords()).isEmpty();   // url 모드는 항상 빈 배열
		assertThat(item.post().snapshots()).hasSize(1);
		assertThat(item.post().snapshots().get(0).views()).isEqualTo(500L);   // REELS는 views 보존
		assertThat(item.post().recentComments()).hasSize(1);
		assertThat(item.post().recentComments().get(0).author()).isEqualTo("au***ab");
		// 답글 선행 @핸들도 author와 같은 규칙으로 마스킹(2026-09-03, FE 피드백 #4-D)
		assertThat(item.post().recentComments().get(0).reply().text()).isEqualTo("@au***ab 작성자 답글");
	}

	@Test
	void account_detecting_행은_post가_null이다() {
		LocalDate registeredOn = LocalDate.now();
		long targetId = seedTarget("ACCOUNT", "glowdeep", "WATCHING", null, null, null, false, null);
		long itemId = seedAccountItem(null, registeredOn, "glowdeep");
		itemRepository.confirmTarget(itemId, targetId);

		TrackingItemResponse item = assembler.assembleList(userId).items().get(0);

		assertThat(item.status()).isEqualTo("detecting");
		assertThat(item.post()).isNull();
		assertThat(item.nextCheckAt()).isNotNull();
	}

	@Test
	void hidden_상태는_hiddenAt이_채워진_post를_돌려준다() {
		OffsetDateTime hiddenAt = OffsetDateTime.now();
		LocalDate registeredOn = LocalDate.now().minusDays(3);
		long targetId = seedTarget("ACCOUNT", "glowdeep", "TRACKING", "SHORT1",
				registeredOn.atStartOfDay(java.time.ZoneId.of("Asia/Seoul")).toOffsetDateTime(), hiddenAt, false, null);
		long itemId = seedAccountItem(null, registeredOn, "glowdeep");
		itemRepository.confirmTarget(itemId, targetId);
		seedPostMeta("SHORT1", "glowdeep", "FEED", registeredOn, "", null);
		seedSuccessfulSweep(LocalDate.now());

		TrackingItemResponse item = assembler.assembleList(userId).items().get(0);

		assertThat(item.status()).isEqualTo("hidden");
		assertThat(item.post()).isNotNull();
		assertThat(item.post().hiddenAt()).isNotNull();
	}

	@Test
	void error_상태는_fetch_failing으로_유도된다() {
		LocalDate registeredOn = LocalDate.now();
		long targetId = seedTarget("ACCOUNT", "glowdeep", "TRACKING", "SHORT1", null, null, true, null);
		long itemId = seedAccountItem(null, registeredOn, "glowdeep");
		itemRepository.confirmTarget(itemId, targetId);

		TrackingItemResponse item = assembler.assembleList(userId).items().get(0);

		assertThat(item.status()).isEqualTo("error");
	}

	@Test
	void EXPIRED_추적게시물_있으면_ended_없으면_not_uploaded() {
		LocalDate registeredOn = LocalDate.now().minusDays(40);
		long endedTargetId = seedTarget("ACCOUNT", "acc_ended", "EXPIRED", "SHORT1", null, null, false, null);
		long endedItemId = seedAccountItem(null, registeredOn, "acc_ended");
		itemRepository.confirmTarget(endedItemId, endedTargetId);

		long notUploadedTargetId = seedTarget("ACCOUNT", "acc_not_uploaded", "EXPIRED", null, null, null, false, null);
		long notUploadedItemId = seedAccountItem(null, registeredOn, "acc_not_uploaded");
		itemRepository.confirmTarget(notUploadedItemId, notUploadedTargetId);

		List<TrackingItemResponse> items = assembler.assembleList(userId).items();

		TrackingItemResponse ended = items.stream().filter(i -> i.handle().equals("acc_ended")).findFirst().orElseThrow();
		TrackingItemResponse notUploaded =
				items.stream().filter(i -> i.handle().equals("acc_not_uploaded")).findFirst().orElseThrow();
		assertThat(ended.status()).isEqualTo("ended");
		assertThat(notUploaded.status()).isEqualTo("not_uploaded");
		assertThat(notUploaded.post()).isNull();
	}

	@Test
	void FEED는_views_shares_reposts를_null로_강제한다() {
		LocalDate registeredOn = LocalDate.now().minusDays(2);
		long targetId = seedTarget("ACCOUNT", "glowdeep", "TRACKING", "SHORT1", null, null, false, null);
		long itemId = seedAccountItem(null, registeredOn, "glowdeep");
		itemRepository.confirmTarget(itemId, targetId);
		seedPostMeta("SHORT1", "glowdeep", "FEED", registeredOn, "피드 캡션", null);
		seedSnapshot("SHORT1", "glowdeep", registeredOn.plusDays(1), "FEED", 30L, 999L, 999L);   // monitoring이 값을 줘도
		seedSuccessfulSweep(LocalDate.now());

		TrackingItemResponse item = assembler.assembleList(userId).items().get(0);

		var snapshot = item.post().snapshots().get(0);
		assertThat(snapshot.likes()).isEqualTo(30L);
		assertThat(snapshot.views()).isNull();
		assertThat(snapshot.shares()).isNull();
		assertThat(snapshot.reposts()).isNull();
	}

	@Test
	void 스냅샷_창은_등록일_이전과_워터마크_이후를_배제한다() {
		LocalDate registeredOn = LocalDate.now().minusDays(5);
		long targetId = seedTarget("ACCOUNT", "glowdeep", "TRACKING", "SHORT1", null, null, false, null);
		long itemId = seedAccountItem(null, registeredOn, "glowdeep");
		itemRepository.confirmTarget(itemId, targetId);
		seedPostMeta("SHORT1", "glowdeep", "REELS", registeredOn, "캡션", null);
		seedSnapshot("SHORT1", "glowdeep", registeredOn.minusDays(1), "REELS", 1L, 1L, 1L);   // 소급분 — 제외
		seedSnapshot("SHORT1", "glowdeep", registeredOn, "REELS", 10L, 10L, 10L);              // 등록일 당일 — 포함
		seedSnapshot("SHORT1", "glowdeep", LocalDate.now().plusDays(3), "REELS", 999L, 999L, 999L);   // 워터마크 이후 — 제외
		seedSuccessfulSweep(LocalDate.now());   // 워터마크 = 오늘

		TrackingItemResponse item = assembler.assembleList(userId).items().get(0);

		assertThat(item.post().snapshots()).hasSize(1);
		assertThat(item.post().snapshots().get(0).likes()).isEqualTo(10L);
	}

	@Test
	void 성공한_스윕이_없으면_스냅샷은_항상_빈_배열이다() {
		LocalDate registeredOn = LocalDate.now().minusDays(1);
		long targetId = seedTarget("ACCOUNT", "glowdeep", "TRACKING", "SHORT1", null, null, false, null);
		long itemId = seedAccountItem(null, registeredOn, "glowdeep");
		itemRepository.confirmTarget(itemId, targetId);
		seedPostMeta("SHORT1", "glowdeep", "REELS", registeredOn, "캡션", null);
		seedSnapshot("SHORT1", "glowdeep", registeredOn, "REELS", 10L, 10L, 10L);
		// seedSuccessfulSweep 호출 없음 — 성공한 배치가 없다.

		TrackingItemAssembler.AssembledList assembled = assembler.assembleList(userId);

		assertThat(assembled.lastCollectedAt()).isNull();
		assertThat(assembled.items().get(0).post().snapshots()).isEmpty();
	}

	@Test
	void matchedKeywords는_account_모드_target에서_읽고_null이면_빈_배열_폴백() {
		LocalDate registeredOn = LocalDate.now();
		long targetId = seedTarget("ACCOUNT", "glowdeep", "TRACKING", "SHORT1", null, null, false, "[\"샤넬\",\"립스틱\"]");
		long itemId = seedAccountItem(null, registeredOn, "glowdeep");
		itemRepository.confirmTarget(itemId, targetId);
		seedPostMeta("SHORT1", "glowdeep", "REELS", registeredOn, "캡션", null);

		long targetId2 = seedTarget("ACCOUNT", "another", "TRACKING", "SHORT2", null, null, false, null);
		long itemId2 = seedAccountItem(null, registeredOn, "another");
		itemRepository.confirmTarget(itemId2, targetId2);
		seedPostMeta("SHORT2", "another", "REELS", registeredOn, "캡션2", null);

		List<TrackingItemResponse> items = assembler.assembleList(userId).items();

		TrackingItemResponse withKeywords = items.stream().filter(i -> i.handle().equals("glowdeep")).findFirst().orElseThrow();
		TrackingItemResponse withoutKeywords = items.stream().filter(i -> i.handle().equals("another")).findFirst().orElseThrow();
		assertThat(withKeywords.post().matchedKeywords()).containsExactly("샤넬", "립스틱");
		assertThat(withoutKeywords.post().matchedKeywords()).isEmpty();
	}

	@Test
	void 댓글은_최신순_상한_45건이고_캠페인_짝이_함께_조립된다() {
		// 상한(45)은 monitoring 수집 상한(comment-pages=3, 45건)과 동일 — 07-31 결함 B: 저장은
		// 45건까지 하는데 서빙이 8건에 잘려 화면에 못 보이던 문제의 회귀 테스트.
		LocalDate registeredOn = LocalDate.now();
		CampaignRow campaign = campaignRepository.insert(userId, "테스트 캠페인", null, null, null, null, null, null);
		long targetId = seedTarget("ACCOUNT", "glowdeep", "TRACKING", "SHORT1", null, null, false, null);
		long itemId = seedAccountItem(campaign.id(), registeredOn, "glowdeep");
		itemRepository.confirmTarget(itemId, targetId);
		seedPostMeta("SHORT1", "glowdeep", "REELS", registeredOn, "캡션", null);
		for (int i = 0; i < 50; i++) {
			seedComment("SHORT1", "c" + i, "author_" + i, "댓글" + i, i, OffsetDateTime.now().plusMinutes(i), null);
		}

		TrackingItemResponse item = assembler.assembleList(userId).items().get(0);

		assertThat(item.post().recentComments()).hasSize(45);
		assertThat(item.post().recentComments().get(0).id()).isEqualTo("c49");   // 최신순
		assertThat(item.campaignId()).isEqualTo(String.valueOf(campaign.id()));
		assertThat(item.campaignName()).isEqualTo("테스트 캠페인");
	}

	@Test
	void meta는_total_lastCollectedAt_today를_담는다() {
		LocalDate registeredOn = LocalDate.now();
		seedAccountItem(null, registeredOn, "acc1");
		seedSuccessfulSweep(LocalDate.now().minusDays(1));

		TrackingItemAssembler.AssembledList assembled = assembler.assembleList(userId);

		assertThat(assembled.items()).hasSize(1);
		assertThat(assembled.lastCollectedAt()).isNotNull();
		assertThat(assembled.today()).isEqualTo(LocalDate.now(com.celfit.was.v1.common.KstTimestamps.KST));
	}

	// ── 댓글 매핑 규칙(결손 제외·마스킹) — DB NOT NULL이 실제 결손 행 삽입을 막으므로 순수 단위 테스트로 ──

	@Test
	void 결손_댓글은_통째로_제외된다() {
		PostCommentRow missingBody = new PostCommentRow("SHORT1", "c1", "author", null, 1L, OffsetDateTime.now(), null);
		PostCommentRow missingLikes = new PostCommentRow("SHORT1", "c2", "author", "본문", null, OffsetDateTime.now(), null);
		PostCommentRow ok = new PostCommentRow("SHORT1", "c3", "glowdeep_92", "본문", 5L, OffsetDateTime.now(), "답글");

		assertThat(TrackingItemAssembler.toCommentResponse(missingBody)).isNull();
		assertThat(TrackingItemAssembler.toCommentResponse(missingLikes)).isNull();
		var mapped = TrackingItemAssembler.toCommentResponse(ok);
		assertThat(mapped.author()).isEqualTo("gl***92");
		assertThat(mapped.reply().text()).isEqualTo("답글");
	}

	@Test
	void 스냅샷_매핑은_FEED_강제_외에는_원값을_보존한다() {
		TrackedSnapshotRow reels = new TrackedSnapshotRow("SHORT1", LocalDate.now(), "REELS", 1L, false, 2L, 3L, 4L, 5L, false, 6L);
		var mapped = TrackingItemAssembler.toSnapshotResponse(reels, false);
		assertThat(mapped.views()).isEqualTo(3L);
		assertThat(mapped.shares()).isEqualTo(5L);
		assertThat(mapped.reposts()).isEqualTo(6L);
		assertThat(mapped.likesHidden()).isFalse();
	}

	@Test
	void 불명_content_type_스냅샷은_REELS_아니면_보수적으로_FEED로_접는다() {
		TrackedSnapshotRow unknown =
				new TrackedSnapshotRow("SHORT1", LocalDate.now(), "SOMETHING_UNKNOWN", 1L, false, 2L, 3L, 4L, 5L, true, 6L);
		var mapped = TrackingItemAssembler.toSnapshotResponse(unknown, false);
		assertThat(mapped.views()).isNull();
		assertThat(mapped.shares()).isNull();
		assertThat(mapped.reposts()).isNull();
		// 피드로 접힌 스냅샷은 공유 자체가 미지원(null 강제) — "숨김" 신호도 함께 접는다.
		assertThat(mapped.sharesHidden()).isFalse();
	}

	/** 좋아요 숨김 관측 — likes null과 별개로 플래그가 FE까지 통과해야 "숨김"과 "수집 실패"가 구분된다. */
	@Test
	void 좋아요_숨김_스냅샷은_플래그가_응답까지_통과한다() {
		TrackedSnapshotRow hidden = new TrackedSnapshotRow("SHORT1", LocalDate.now(), "REELS", null, true, 2L, 3L, 4L, 5L, false, 6L);
		var mapped = TrackingItemAssembler.toSnapshotResponse(hidden, false);
		assertThat(mapped.likes()).isNull();
		assertThat(mapped.likesHidden()).isTrue();
	}

	/** 공유 숨김 관측(v2.7) — shares null과 별개로 플래그가 FE까지 통과해야 "비공개"와 "수집 실패"가 구분된다. */
	@Test
	void 공유_숨김_스냅샷은_플래그가_응답까지_통과한다() {
		TrackedSnapshotRow hidden = new TrackedSnapshotRow("SHORT1", LocalDate.now(), "REELS", 1L, false, 2L, 3L, 4L, null, true, 6L);
		var mapped = TrackingItemAssembler.toSnapshotResponse(hidden, false);
		assertThat(mapped.shares()).isNull();
		assertThat(mapped.sharesHidden()).isTrue();
	}

	// ── 리뷰 반영(2026-07-30) — pending 만료 유도 ────────────────────────────────

	@Test
	void pending_url_행이_만료되면_ended이고_post는_없다() {
		LocalDate registeredOn = LocalDate.now().minusDays(30);   // trackingDays(14)와 합쳐 이미 만료.
		seedUrlItem(null, registeredOn, "SHORT1", "https://www.instagram.com/p/SHORT1/");
		// target 확정 없음 — 끝까지 pending인 채로 만료된 경우를 재현한다.

		TrackingItemResponse item = assembler.assembleList(userId).items().get(0);

		assertThat(item.status()).isEqualTo("ended");
		assertThat(item.post()).isNull();   // 계약 경계: "collecting으로 기간이 만료된 url 모드는 post가 null인 빈 상자"
	}

	@Test
	void pending_account_행이_만료되면_not_uploaded다() {
		LocalDate registeredOn = LocalDate.now().minusDays(30);
		seedAccountItem(null, registeredOn, "glowdeep");

		TrackingItemResponse item = assembler.assembleList(userId).items().get(0);

		assertThat(item.status()).isEqualTo("not_uploaded");
		assertThat(item.post()).isNull();
	}

	@Test
	void pending_행이_미만료면_기존대로_collecting_detecting을_유지한다() {
		LocalDate registeredOn = LocalDate.now();   // 방금 등록 — 전혀 만료 아님.
		seedUrlItem(null, registeredOn, "SHORT1", "https://www.instagram.com/p/SHORT1/");
		seedAccountItem(null, registeredOn, "glowdeep");

		List<TrackingItemResponse> items = assembler.assembleList(userId).items();

		assertThat(items).extracting(TrackingItemResponse::status).containsExactlyInAnyOrder("collecting", "detecting");
	}

	// ── profile_meta 이미지 URL 스킴 방어(결함 ②) ──────────────────────────────

	@Test
	void 유효한_profile_image_url은_그대로_서빙된다() {
		LocalDate registeredOn = LocalDate.now();
		long targetId = seedTarget("ACCOUNT", "glowdeep", "TRACKING", "SHORT1", null, null, false, null);
		long itemId = seedAccountItem(null, registeredOn, "glowdeep");
		itemRepository.confirmTarget(itemId, targetId);
		seedProfileMeta("glowdeep", "표시이름", "https://img.cdn/1.jpg");

		TrackingItemResponse item = assembler.assembleList(userId).items().get(0);

		assertThat(item.profileImageUrl()).isEqualTo("https://img.cdn/1.jpg");
	}

	/** 저장 측(monitoring)이 막아도 이미 DB에 박힌 무효 스킴 값이 있을 수 있어 서빙 측도 이중 방어한다. */
	@Test
	void 무효_스킴_profile_image_url은_null로_서빙된다() {
		LocalDate registeredOn = LocalDate.now();
		long targetId = seedTarget("ACCOUNT", "glowdeep", "TRACKING", "SHORT1", null, null, false, null);
		long itemId = seedAccountItem(null, registeredOn, "glowdeep");
		itemRepository.confirmTarget(itemId, targetId);
		seedProfileMeta("glowdeep", "표시이름", "exception://");

		TrackingItemResponse item = assembler.assembleList(userId).items().get(0);

		assertThat(item.profileImageUrl()).isNull();
	}

	// ── profile_meta 이미지 아카이브 우선 서빙(결함 ①, 설계 스펙 §3-1) ────────────

	/** image_object_path(monitoring 자체 아카이브 결과)가 있으면 원본 CDN URL보다 그걸 우선 서빙한다. */
	@Test
	void image_object_path가_있으면_아카이브_경로를_우선_서빙한다() {
		LocalDate registeredOn = LocalDate.now();
		long targetId = seedTarget("ACCOUNT", "glowdeep", "TRACKING", "SHORT1", null, null, false, null);
		long itemId = seedAccountItem(null, registeredOn, "glowdeep");
		itemRepository.confirmTarget(itemId, targetId);
		seedProfileMeta("glowdeep", "표시이름", "https://img.cdn/1.jpg", "monitor-profile/glowdeep.jpg");

		TrackingItemResponse item = assembler.assembleList(userId).items().get(0);

		assertThat(item.profileImageUrl()).isEqualTo("/img/monitor-profile/glowdeep.jpg");
	}

	// ── post_meta 썸네일 URL 스킴 방어·아카이브 우선 서빙(트랙 KK 확장, profile_meta와 동형) ────

	@Test
	void 유효한_thumbnail_url은_그대로_서빙된다() {
		LocalDate registeredOn = LocalDate.now();
		long targetId = seedTarget("ACCOUNT", "glowdeep", "TRACKING", "SHORT1", null, null, false, null);
		long itemId = seedAccountItem(null, registeredOn, "glowdeep");
		itemRepository.confirmTarget(itemId, targetId);
		seedPostMeta("SHORT1", "glowdeep", "FEED", registeredOn, "캡션", "https://cdn.example/thumb.jpg");
		seedSuccessfulSweep(LocalDate.now());

		TrackingItemResponse item = assembler.assembleList(userId).items().get(0);

		assertThat(item.post().thumbnailUrl()).isEqualTo("https://cdn.example/thumb.jpg");
	}

	/** 저장 측(monitoring)이 막아도 이미 DB에 박힌 무효 스킴 값이 있을 수 있어 서빙 측도 이중 방어한다. */
	@Test
	void 무효_스킴_thumbnail_url은_null로_서빙된다() {
		LocalDate registeredOn = LocalDate.now();
		long targetId = seedTarget("ACCOUNT", "glowdeep", "TRACKING", "SHORT1", null, null, false, null);
		long itemId = seedAccountItem(null, registeredOn, "glowdeep");
		itemRepository.confirmTarget(itemId, targetId);
		seedPostMeta("SHORT1", "glowdeep", "FEED", registeredOn, "캡션", "exception://");
		seedSuccessfulSweep(LocalDate.now());

		TrackingItemResponse item = assembler.assembleList(userId).items().get(0);

		assertThat(item.post().thumbnailUrl()).isNull();
	}

	/** image_object_path(monitoring 자체 썸네일 아카이브 결과)가 있으면 원본 CDN URL보다 그걸 우선 서빙한다. */
	@Test
	void 게시물_썸네일_image_object_path가_있으면_아카이브_경로를_우선_서빙한다() {
		LocalDate registeredOn = LocalDate.now();
		long targetId = seedTarget("ACCOUNT", "glowdeep", "TRACKING", "SHORT1", null, null, false, null);
		long itemId = seedAccountItem(null, registeredOn, "glowdeep");
		itemRepository.confirmTarget(itemId, targetId);
		seedPostMeta("SHORT1", "glowdeep", "FEED", registeredOn, "캡션", "https://cdn.example/thumb.jpg",
				"monitor-post/SHORT1.jpg");
		seedSuccessfulSweep(LocalDate.now());

		TrackingItemResponse item = assembler.assembleList(userId).items().get(0);

		assertThat(item.post().thumbnailUrl()).isEqualTo("/img/monitor-post/SHORT1.jpg");
	}

	// ── 리뷰 반영(2026-07-30) — campaignId·campaignName 짝 방어 ──────────────────

	// ── monitoring_items.keywords NULL 방어(레거시 개인 추적 아이템, readKeywordRule) ────────

	/**
	 * readKeywordRule이 null 체크 없이 objectMapper.readValue를 호출해 keywords가 NULL인 레거시
	 * account 아이템에서 IllegalArgumentException → 500이 나던 결함의 회귀 테스트. readMatchedKeywords와
	 * 동일하게 null이면 빈 KeywordRule로 폴백해야 한다.
	 */
	@Test
	void keywords가_NULL인_레거시_계정_아이템도_예외_없이_빈_규칙으로_조립된다() {
		LocalDate registeredOn = LocalDate.now();
		long itemId = itemRepository.insertPending(userId, "account", UUID.randomUUID(), null, "glowdeep", null, null,
				14, registeredOn);
		long targetId = seedTarget("ACCOUNT", "glowdeep", "WATCHING", null, null, null, false, null);
		itemRepository.confirmTarget(itemId, targetId);

		TrackingItemResponse item = assembler.assembleList(userId).items().get(0);

		assertThat(item.keywords()).isNotNull();
		assertThat(item.keywords().and()).isEmpty();
		assertThat(item.keywords().or()).isEmpty();
		assertThat(item.keywords().exclude()).isEmpty();
	}

	@Test
	void campaignId가_있어도_소유자가_다른_캠페인이면_짝을_맞춰_둘_다_null이다() {
		// FK는 campaign_id가 "존재하는 행"이면 통과시킨다 — 다른 유저 소유 캠페인을 가리키는 이론상
		// 오상태(orphan)를 흉내내, campaignRepository.findByUser(이 유저)의 맵에 없는 경우를 재현한다.
		long otherUserId = jdbcClient.sql("INSERT INTO app.users (email, password_hash) VALUES (:email, 'x') RETURNING id")
				.param("email", "mon-assembler-other-" + UUID.randomUUID() + "@test.io")
				.query(Long.class).single();
		CampaignRow foreignCampaign = campaignRepository.insert(otherUserId, "남의 캠페인", null, null, null, null, null, null);
		LocalDate registeredOn = LocalDate.now();
		seedAccountItem(foreignCampaign.id(), registeredOn, "glowdeep");

		TrackingItemResponse item = assembler.assembleList(userId).items().get(0);

		assertThat(item.campaignId()).isNull();
		assertThat(item.campaignName()).isNull();
	}
}
