package com.celfit.was;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.celfit.was.monitoring.CampaignRepository;
import com.celfit.was.monitoring.CampaignRow;
import com.celfit.was.monitoring.MonitoringItemRepository;
import com.celfit.was.monitoring.RegistrationRepository;
import com.celfit.was.v1.account.SignupCodeRepository;
import com.celfit.was.v1.admin.AdminAuditLogRepository;
import com.celfit.was.v1.common.KstTimestamps;
import com.celfit.was.v1.saved.V1SavedRepository;
import jakarta.servlet.http.Cookie;
import java.sql.Connection;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 어드민 조회 API 4종 실 DB 통합 검증(설계 2026-08-01 §4) — users 목록/상세·monitoring/registrations·
 * audit-logs. monitoring.enabled=true로 띄워 실 target/post_meta/post_snapshot까지 물린다
 * (MonitoringEnabledConfigTest와 동일 관용구 — 같은 POSTGRES 컨테이너를 monitoring DB로도 재사용).
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {"monitoring.enabled=true", "monitoring.digest.weekly-cron=-",
		"monitoring.digest.weekly-catchup-cron=-", "monitoring.recover.cron=-",
		// @BeforeEach마다 adminSession을 새로 로그인해 얻는다 — 테스트 메서드 수가 늘수록 같은
		// (email|ip) 키가 분당 기본 상한(10, RateLimiter)에 부딪힌다(08-02, 12번째 테스트 추가 후
		// 429 실측). 실제 레이트리밋 동작 자체는 RateLimiterTest가 별도로 커버하므로 여기선 상한만
		// 넉넉히 올린다.
		"was.rate-limit.per-minute=100"})
class AdminQueryApiIntegrationTest extends IntegrationTest {

	@DynamicPropertySource
	static void monitoringDatasource(DynamicPropertyRegistry registry) {
		registry.add("monitoring.datasource.url", POSTGRES::getJdbcUrl);
		registry.add("monitoring.datasource.username", POSTGRES::getUsername);
		registry.add("monitoring.datasource.password", POSTGRES::getPassword);
	}

	private static final String PASSWORD = "Passw0rd!";
	private static final String ADMIN_EMAIL = "admin-query@test.io";

	@Autowired
	MockMvc mockMvc;
	@Autowired
	JdbcClient jdbcClient;
	@Autowired
	DataSource dataSource;
	@Autowired
	PasswordEncoder passwordEncoder;
	@Autowired
	MonitoringItemRepository itemRepository;
	@Autowired
	CampaignRepository campaignRepository;
	@Autowired
	RegistrationRepository registrationRepository;
	@Autowired
	V1SavedRepository savedRepository;
	@Autowired
	SignupCodeRepository signupCodeRepository;
	@Autowired
	AdminAuditLogRepository auditLogRepository;

	private Cookie adminSession;

	@BeforeEach
	void setUp() throws Exception {
		try (Connection conn = dataSource.getConnection()) {
			ScriptUtils.executeSqlScript(conn, new ClassPathResource("monitoring-schema.sql"));
		}
		jdbcClient.sql("""
				TRUNCATE target, detected_candidate, profile_snapshot, post_snapshot,
				         post_meta, post_comment, profile_meta, sweep_run RESTART IDENTITY
				""").update();

		// analysis 미러(content_saved 이벤트 카드 조회용) — V1SavedRepositoryTest와 동일 형상.
		jdbcClient.sql("DROP TABLE IF EXISTS content_analyses").update();
		jdbcClient.sql("DROP TABLE IF EXISTS contents").update();
		jdbcClient.sql("DROP TABLE IF EXISTS accounts").update();
		jdbcClient.sql("DROP TABLE IF EXISTS image_assets").update();
		jdbcClient.sql("DROP TABLE IF EXISTS group_purchase_judgments").update();
		jdbcClient.sql("""
				CREATE TABLE accounts (handle text PRIMARY KEY, display_name text,
				    profile_image_url text, followers bigint)
				""").update();
		jdbcClient.sql("""
				CREATE TABLE contents (short_code text PRIMARY KEY, account_handle text NOT NULL,
				    thumbnail_url text, caption text, posted_at timestamptz, content_type text,
				    video_duration numeric, original_url text, views bigint, likes bigint, comments bigint,
				    hype_score bigint, metric_captured_at timestamptz, hype_score_precise numeric)
				""").update();
		jdbcClient.sql("""
				CREATE TABLE content_analyses (short_code text PRIMARY KEY, main_category text,
				    sub_categories jsonb, ad_type text, detected_brands jsonb, detected_products jsonb,
				    detected_distributors jsonb)
				""").update();
		jdbcClient.sql("""
				CREATE TABLE image_assets (kind text NOT NULL, key text NOT NULL, object_path text NOT NULL,
				    source_name text NOT NULL, archived_at timestamptz NOT NULL DEFAULT now(),
				    PRIMARY KEY (kind, key))
				""").update();
		// ContentCardRow.SELECT의 gpj 조인 재료(2026-09-03 공동구매 판정 서버화) — AdminUserEventAssembler가
		// content_saved 이벤트 라벨용으로 savedRepository.findCards를 타서 이 테이블이 없으면 500이 난다.
		jdbcClient.sql("""
				CREATE TABLE group_purchase_judgments (short_code text PRIMARY KEY, verdict boolean,
				    tier text NOT NULL, reason text, judged_caption_hash text NOT NULL,
				    judged_at timestamptz NOT NULL, model text)
				""").update();

		// app 쪽 어드민 조회 대상 테이블 — 자식부터 정리(FK).
		jdbcClient.sql("DELETE FROM app.admin_audit_logs").update();
		jdbcClient.sql("DELETE FROM app.monitoring_registration_entries").update();
		jdbcClient.sql("DELETE FROM app.monitoring_registrations").update();
		jdbcClient.sql("DELETE FROM app.monitoring_items").update();
		jdbcClient.sql("DELETE FROM app.monitoring_campaigns").update();
		jdbcClient.sql("DELETE FROM app.saved_contents").update();
		jdbcClient.sql("DELETE FROM app.saved_influencers").update();
		jdbcClient.sql("DELETE FROM app.signup_codes").update();
		jdbcClient.sql("DELETE FROM app.users").update();

		insertUser(ADMIN_EMAIL, "ADMIN", "관리자", null);
		// 어드민 계정 자체가 목록 정렬 검증에 섞여 들지 않도록 created_at을 과거로 고정한다.
		jdbcClient.sql("UPDATE app.users SET created_at = timestamptz '2020-01-01' WHERE email = :email")
				.param("email", ADMIN_EMAIL).update();
		adminSession = login(ADMIN_EMAIL);
	}

	// --- GET /v1/admin/users ---

	@Test
	void 목록은_created_at_역순이고_취소포함_카운트_query_필터_lastActiveAt_null을_반영한다() throws Exception {
		long olderUser = insertUser("alpha@test.io", "USER", "Alpha", "portal_search");
		jdbcClient.sql("UPDATE app.users SET created_at = now() - interval '2 day' WHERE id = :id")
				.param("id", olderUser).update();
		long newerUser = insertUser("beta@test.io", "USER", "Beta", "referral");
		jdbcClient.sql("UPDATE app.users SET created_at = now() - interval '1 day' WHERE id = :id")
				.param("id", newerUser).update();

		campaignRepository.insert(olderUser, "캠페인A", null, null, null, null, null, null);
		long activeItem = itemRepository.insertPending(olderUser, "account", UUID.randomUUID(), null, "glowdeep",
				null, "{}", 14, LocalDate.now());
		long canceledItem = itemRepository.insertPending(olderUser, "account", UUID.randomUUID(), null, "other",
				null, "{}", 14, LocalDate.now());
		itemRepository.markCanceled(canceledItem, "detecting", OffsetDateTime.now());
		savedRepository.upsertInfluencer(olderUser, "someinfluencer", null);

		mockMvc.perform(get("/v1/admin/users").cookie(adminSession))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data[0].email").value("beta@test.io"))
				.andExpect(jsonPath("$.data[1].email").value("alpha@test.io"))
				.andExpect(jsonPath("$.data[1].campaignCount").value(1))
				// monitoringCount는 취소 포함 전량 누적 — 활성 1 + 취소 1 = 2.
				.andExpect(jsonPath("$.data[1].monitoringCount").value(2))
				.andExpect(jsonPath("$.data[1].savedCount").value(1))
				.andExpect(jsonPath("$.data[1].lastActiveAt").value(org.hamcrest.Matchers.nullValue()))
				// companyName은 상세와 동일하게 미설정 시 ''(app.users.company_name NOT NULL DEFAULT '').
				.andExpect(jsonPath("$.data[1].companyName").value(""))
				.andExpect(jsonPath("$.meta.total").value(org.hamcrest.Matchers.greaterThanOrEqualTo(3)));

		mockMvc.perform(get("/v1/admin/users").cookie(adminSession).param("query", "alpha"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.length()").value(1))
				.andExpect(jsonPath("$.data[0].email").value("alpha@test.io"));

		assertThat(activeItem).isNotEqualTo(canceledItem);   // 시딩 자체의 자명한 확인(가독용)
	}

	@Test
	void 목록의_companyName은_상세와_동일하게_설정값을_그대로_내보낸다() throws Exception {
		long userId = insertUser("company@test.io", "USER", "컴퍼니", null);
		jdbcClient.sql("UPDATE app.users SET company_name = :companyName WHERE id = :id")
				.param("companyName", "글로우딥 주식회사").param("id", userId).update();

		mockMvc.perform(get("/v1/admin/users").cookie(adminSession).param("query", "company@test.io"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data[0].companyName").value("글로우딥 주식회사"));

		mockMvc.perform(get("/v1/admin/users/" + userId).cookie(adminSession))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.companyName").value("글로우딥 주식회사"));
	}

	// --- GET /v1/admin/users/{id} ---

	@Test
	void 상세는_부재시_404다() throws Exception {
		mockMvc.perform(get("/v1/admin/users/999999999").cookie(adminSession))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
	}

	@Test
	void 상세는_signupCode_역참조와_5종_이벤트_라벨과_정렬을_반영한다() throws Exception {
		long userId = insertUser("detail@test.io", "USER", "디테일", "referral");
		jdbcClient.sql("INSERT INTO app.signup_codes (code, channel, used_by) VALUES ('INVITE-1', 'DM', :userId)")
				.param("userId", userId).update();

		OffsetDateTime base = OffsetDateTime.now().minusDays(10);
		// signup 이벤트(users.created_at)를 가장 과거로 고정해야 아래 4개 합성 이벤트와의 상대 순서가
		// (기본값인 "지금")이 아니라 의도한 base+1h~+4h 기준으로 결정된다.
		jdbcClient.sql("UPDATE app.users SET created_at = :at WHERE id = :id")
				.param("at", base).param("id", userId).update();
		CampaignRow campaign = campaignRepository.insert(userId, "여름 캠페인", null, null, null, null, null, null);
		jdbcClient.sql("UPDATE app.monitoring_campaigns SET created_at = :at WHERE id = :id")
				.param("at", base.plusHours(1)).param("id", campaign.id()).update();

		long regId = registrationRepository.insert(userId, 14, null);
		registrationRepository.insertEntry(regId, 1, "glowdeep", "account", "pending", null, null, null, null);
		jdbcClient.sql("UPDATE app.monitoring_registrations SET requested_at = :at WHERE id = :id")
				.param("at", base.plusHours(2)).param("id", regId).update();

		seedContent("SC1", "beautyhandle", "REELS");
		var savedContent = savedRepository.upsertContent(userId, "SC1", null);
		jdbcClient.sql("UPDATE app.saved_contents SET created_at = :at WHERE user_id = :uid AND short_code = :sc")
				.param("at", base.plusHours(3)).param("uid", userId).param("sc", "SC1").update();

		savedRepository.upsertInfluencer(userId, "glamqueen", null);
		jdbcClient.sql("UPDATE app.saved_influencers SET created_at = :at WHERE user_id = :uid AND handle = :h")
				.param("at", base.plusHours(4)).param("uid", userId).param("h", "glamqueen").update();

		mockMvc.perform(get("/v1/admin/users/" + userId).cookie(adminSession))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.signupCode").value("INVITE-1"))
				.andExpect(jsonPath("$.data.companyName").value(""))
				.andExpect(jsonPath("$.data.events.length()").value(5))
				// at 내림차순 — 가장 최근인 influencer_saved가 맨 위.
				.andExpect(jsonPath("$.data.events[0].type").value("influencer_saved"))
				.andExpect(jsonPath("$.data.events[0].label").value("인플루언서 저장 (@glamqueen)"))
				.andExpect(jsonPath("$.data.events[1].type").value("content_saved"))
				.andExpect(jsonPath("$.data.events[1].label").value("콘텐츠 저장 (@beautyhandle 릴스)"))
				.andExpect(jsonPath("$.data.events[2].type").value("monitoring_registered"))
				.andExpect(jsonPath("$.data.events[2].label").value("모니터링 등록 (@glowdeep 1건)"))
				.andExpect(jsonPath("$.data.events[3].type").value("campaign_created"))
				.andExpect(jsonPath("$.data.events[3].label").value("캠페인 '여름 캠페인' 생성"))
				.andExpect(jsonPath("$.data.events[4].type").value("signup"))
				.andExpect(jsonPath("$.data.events[4].label").value("가입 (초대코드 INVITE-1)"));

		assertThat(savedContent.shortCode()).isEqualTo("SC1");
	}

	@Test
	void 상세_이벤트는_100건에서_절단된다() throws Exception {
		long userId = insertUser("bulk@test.io", "USER", "벌크", null);
		OffsetDateTime base = OffsetDateTime.now().minusDays(200);
		// signup 이벤트(users.created_at)를 합성 이벤트 전부보다 더 과거로 고정 — 정렬 상위 100건이
		// 전부 influencer_saved가 되게 해 절단 경계(handle119~handle20)를 단순하게 검증한다.
		jdbcClient.sql("UPDATE app.users SET created_at = :at WHERE id = :id")
				.param("at", base.minusDays(1)).param("id", userId).update();
		for (int i = 0; i < 120; i++) {
			savedRepository.upsertInfluencer(userId, "handle" + i, null);
			jdbcClient.sql("UPDATE app.saved_influencers SET created_at = :at WHERE user_id = :uid AND handle = :h")
					.param("at", base.plusHours(i)).param("uid", userId).param("h", "handle" + i).update();
		}

		mockMvc.perform(get("/v1/admin/users/" + userId).cookie(adminSession))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.events.length()").value(100))
				// 최신 120개 중 상위 100개만 — handle119가 가장 최근(가장 늦은 created_at), handle20이 100번째.
				.andExpect(jsonPath("$.data.events[0].label").value("인플루언서 저장 (@handle119)"))
				.andExpect(jsonPath("$.data.events[99].label").value("인플루언서 저장 (@handle20)"));
	}

	@Test
	void 가입_이벤트는_초대코드_없으면_가입경로_한국어_라벨이다() throws Exception {
		long userId = insertUser("route@test.io", "USER", "라우트", "blog_community");

		mockMvc.perform(get("/v1/admin/users/" + userId).cookie(adminSession))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.signupCode").value(org.hamcrest.Matchers.nullValue()))
				.andExpect(jsonPath("$.data.events[0].type").value("signup"))
				.andExpect(jsonPath("$.data.events[0].label").value("가입 (블로그·커뮤니티)"));
	}

	// --- GET /v1/admin/monitoring/registrations ---

	@Test
	void registrations는_status_필터_불명값_400_counts_불변_userId_필터_캠페인_미지정_정렬을_지킨다() throws Exception {
		long userA = insertUser("rowuser-a@test.io", "USER", "행유저A", null);
		long userB = insertUser("rowuser-b@test.io", "USER", "행유저B", null);

		// userA: TRACKING + fetch_failing → error (캠페인 미배정)
		long errorTargetId = seedTarget("erroracct", "TRACKING", "ERRSHORT", OffsetDateTime.now(), null, true);
		long errorItem = itemRepository.insertPending(userA, "account", UUID.randomUUID(), null, "erroracct", null,
				"{}", 30, LocalDate.now());
		itemRepository.confirmTarget(errorItem, errorTargetId);

		// userB: WATCHING + 등록 8일 전 → detect_stalled
		long watchingTargetId = seedTarget("stalledacct", "WATCHING", null, null, null, false);
		long stalledItem = itemRepository.insertPending(userB, "account", UUID.randomUUID(), null, "stalledacct",
				null, "{}", 30, LocalDate.now().minusDays(8));
		itemRepository.confirmTarget(stalledItem, watchingTargetId);

		mockMvc.perform(get("/v1/admin/monitoring/registrations").cookie(adminSession).param("status", "bogus"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

		MvcResult unfiltered = mockMvc.perform(get("/v1/admin/monitoring/registrations").cookie(adminSession))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.counts.error").value(1))
				.andExpect(jsonPath("$.data.counts.detect_stalled").value(1))
				.andExpect(jsonPath("$.data.counts.healthy").value(0))
				// 심각도순: error가 detect_stalled보다 앞.
				.andExpect(jsonPath("$.data.rows[0].status").value("error"))
				.andExpect(jsonPath("$.data.rows[0].campaignName").value("(캠페인 미지정)"))
				.andExpect(jsonPath("$.data.rows[1].status").value("detect_stalled"))
				.andReturn();
		assertThat(unfiltered.getResponse().getContentAsString()).contains("erroracct");

		mockMvc.perform(get("/v1/admin/monitoring/registrations").cookie(adminSession).param("status", "error"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.rows.length()").value(1))
				.andExpect(jsonPath("$.data.rows[0].handle").value("@erroracct"))
				// counts는 status 필터 반영 전 그대로(계약).
				.andExpect(jsonPath("$.data.counts.error").value(1))
				.andExpect(jsonPath("$.data.counts.detect_stalled").value(1));

		mockMvc.perform(get("/v1/admin/monitoring/registrations").cookie(adminSession)
						.param("userId", String.valueOf(userB)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.rows.length()").value(1))
				.andExpect(jsonPath("$.data.rows[0].userId").value(String.valueOf(userB)))
				.andExpect(jsonPath("$.data.counts.error").value(0))
				.andExpect(jsonPath("$.data.counts.detect_stalled").value(1));
	}

	// --- GET /v1/admin/audit-logs ---

	@Test
	void audit_logs는_필터와_페이지네이션을_지킨다() throws Exception {
		long admin1 = insertUser("audit-admin-1@test.io", "ADMIN", "관리자1", null);
		long admin2 = insertUser("audit-admin-2@test.io", "ADMIN", "관리자2", null);
		long target = insertUser("audit-target@test.io", "USER", "대상", null);

		auditLogRepository.insert(admin1, target, "/v1/me");
		auditLogRepository.insert(admin1, target, "/v1/monitoring/items");
		auditLogRepository.insert(admin2, target, "/v1/me");

		mockMvc.perform(get("/v1/admin/audit-logs").cookie(adminSession)
						.param("adminId", String.valueOf(admin1)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.length()").value(2))
				.andExpect(jsonPath("$.meta.total").value(2));

		mockMvc.perform(get("/v1/admin/audit-logs").cookie(adminSession)
						.param("targetUserId", String.valueOf(target))
						.param("page", "1").param("limit", "2"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.length()").value(2))
				.andExpect(jsonPath("$.meta.total").value(3))
				.andExpect(jsonPath("$.meta.limit").value(2))
				.andExpect(jsonPath("$.meta.offset").value(0));
	}

	// --- GET /v1/admin/campaigns ---

	@Test
	void campaigns는_상태_4종_경계와_registrationCount_생성일_내림차순_seedingTarget_meta_total을_반영한다() throws Exception {
		long userA = insertUser("campaign-user-a@test.io", "USER", "캠페인유저A", null);
		LocalDate today = LocalDate.now(KstTimestamps.KST);

		CampaignRow noDate = campaignRepository.insert(userA, "노데이트", null, null, null, null, null, null);
		CampaignRow pending =
				campaignRepository.insert(userA, "펜딩", null, today.plusDays(5), null, null, null, 50);
		// start_date==end_date==오늘 — 시작·종료 경계값이 둘 다 active로 포함되는지 함께 검증.
		CampaignRow activeBoundary =
				campaignRepository.insert(userA, "액티브경계", null, today, today, null, null, null);
		CampaignRow ended = campaignRepository.insert(userA, "엔디드", null, null, today.minusDays(1), null, null, null);

		OffsetDateTime base = OffsetDateTime.now().minusDays(10);
		updateCampaignCreatedAt(noDate.id(), base);
		updateCampaignCreatedAt(pending.id(), base.plusHours(1));
		updateCampaignCreatedAt(activeBoundary.id(), base.plusHours(2));
		updateCampaignCreatedAt(ended.id(), base.plusHours(3));   // 가장 최근 → 내림차순 맨 앞.

		// activeBoundary에 등록 2건(활성 1 + 취소 1) — registrationCount는 취소 포함 전량 누적.
		itemRepository.insertPending(userA, "account", UUID.randomUUID(), activeBoundary.id(), "someacct", null,
				"{}", 14, LocalDate.now());
		long canceledItem = itemRepository.insertPending(userA, "account", UUID.randomUUID(), activeBoundary.id(),
				"otheracct", null, "{}", 14, LocalDate.now());
		itemRepository.markCanceled(canceledItem, "detecting", OffsetDateTime.now());

		mockMvc.perform(get("/v1/admin/campaigns").cookie(adminSession))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.meta.total").value(4))
				.andExpect(jsonPath("$.data[0].id").value(String.valueOf(ended.id())))
				.andExpect(jsonPath("$.data[0].status").value("ended"))
				.andExpect(jsonPath("$.data[1].id").value(String.valueOf(activeBoundary.id())))
				.andExpect(jsonPath("$.data[1].status").value("active"))
				.andExpect(jsonPath("$.data[1].registrationCount").value(2))
				.andExpect(jsonPath("$.data[1].userId").value(String.valueOf(userA)))
				.andExpect(jsonPath("$.data[1].userName").value("캠페인유저A"))
				.andExpect(jsonPath("$.data[2].id").value(String.valueOf(pending.id())))
				.andExpect(jsonPath("$.data[2].status").value("pending"))
				.andExpect(jsonPath("$.data[2].seedingTarget").value(50))
				.andExpect(jsonPath("$.data[3].id").value(String.valueOf(noDate.id())))
				.andExpect(jsonPath("$.data[3].status").value("no_date"))
				.andExpect(jsonPath("$.data[3].seedingTarget").value(org.hamcrest.Matchers.nullValue()))
				.andExpect(jsonPath("$.data[3].registrationCount").value(0));
	}

	@Test
	void campaigns는_비ADMIN_401_403_게이트를_지킨다() throws Exception {
		insertUser("campaign-gate-user@test.io", "USER", "게이트유저", null);
		Cookie userSession = login("campaign-gate-user@test.io");

		mockMvc.perform(get("/v1/admin/campaigns"))
				.andExpect(status().isUnauthorized());

		mockMvc.perform(get("/v1/admin/campaigns").cookie(userSession))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
	}

	// --- 정합 불변식: users의 health와 registrations의 rows가 같은 모집단 ---

	@Test
	void error_행을_가진_유저의_health는_error이고_그_행이_rows에도_그대로_존재한다() throws Exception {
		long userId = insertUser("invariant@test.io", "USER", "인바리언트", null);
		long targetId = seedTarget("invaccount", "TRACKING", "INVSHORT", OffsetDateTime.now(), null, true);
		long itemId = itemRepository.insertPending(userId, "account", UUID.randomUUID(), null, "invaccount", null,
				"{}", 30, LocalDate.now());
		itemRepository.confirmTarget(itemId, targetId);

		mockMvc.perform(get("/v1/admin/users").cookie(adminSession).param("query", "invariant"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data[0].health").value("error"));

		mockMvc.perform(get("/v1/admin/monitoring/registrations").cookie(adminSession)
						.param("userId", String.valueOf(userId)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.rows.length()").value(1))
				.andExpect(jsonPath("$.data.rows[0].id").value(String.valueOf(itemId)))
				.andExpect(jsonPath("$.data.rows[0].status").value("error"));
	}

	// --- 픽스처 헬퍼 ---

	private long insertUser(String email, String role, String name, String signupRoute) {
		jdbcClient.sql("""
				INSERT INTO app.users (email, password_hash, role, name, user_type, signup_route,
				                       agreed_terms, agreed_privacy, agreed_age14)
				VALUES (:email, :hash, :role, :name, 'brand', :signupRoute, true, true, true)
				ON CONFLICT (email) DO UPDATE SET role = :role, last_active_at = NULL""")
				.param("email", email)
				.param("hash", passwordEncoder.encode(PASSWORD))
				.param("role", role)
				.param("name", name)
				.param("signupRoute", signupRoute)
				.update();
		return jdbcClient.sql("SELECT id FROM app.users WHERE email = :email")
				.param("email", email)
				.query(Long.class)
				.single();
	}

	private Cookie login(String email) throws Exception {
		MvcResult result = mockMvc.perform(post("/v1/auth/login").with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD)))
				.andExpect(status().isOk())
				.andReturn();
		Cookie session = result.getResponse().getCookie("hypenow-session");
		assertThat(session).isNotNull();
		return session;
	}

	private void updateCampaignCreatedAt(long campaignId, OffsetDateTime at) {
		jdbcClient.sql("UPDATE app.monitoring_campaigns SET created_at = :at WHERE id = :id")
				.param("at", at).param("id", campaignId).update();
	}

	private long seedTarget(String username, String status, String trackedShortCode, OffsetDateTime trackedSince,
			OffsetDateTime trackedHiddenAt, boolean fetchFailing) {
		return jdbcClient.sql("""
				INSERT INTO target (type, username, status, tracked_short_code, tracked_since, tracked_hidden_at,
				                    fetch_failing, registration_key, expires_at)
				VALUES ('ACCOUNT', :username, :status, :tracked, :since, :hidden, :failing,
				        gen_random_uuid()::text, now() + interval '30 days')
				RETURNING id
				""")
				.param("username", username)
				.param("status", status)
				.param("tracked", trackedShortCode)
				.param("since", trackedSince)
				.param("hidden", trackedHiddenAt)
				.param("failing", fetchFailing)
				.query(Long.class)
				.single();
	}

	private void seedContent(String shortCode, String handle, String contentType) {
		jdbcClient.sql("""
				INSERT INTO accounts (handle, display_name, profile_image_url, followers)
				VALUES (:handle, :handle, NULL, 1000)
				ON CONFLICT (handle) DO NOTHING
				""").param("handle", handle).update();
		jdbcClient.sql("""
				INSERT INTO contents (short_code, account_handle, thumbnail_url, caption, posted_at, content_type,
				    video_duration, original_url, views, likes, comments, hype_score, metric_captured_at,
				    hype_score_precise)
				VALUES (:sc, :handle, NULL, '캡션', now(), :contentType, 10, 'https://ig/' || :sc, 100, 10, 1, 50,
				    now(), 50)
				""").param("sc", shortCode).param("handle", handle).param("contentType", contentType).update();
		jdbcClient.sql("""
				INSERT INTO content_analyses (short_code, main_category, sub_categories, ad_type, detected_brands,
				    detected_products, detected_distributors)
				VALUES (:sc, 'beauty', '[]'::jsonb, 'organic', NULL, NULL, NULL)
				""").param("sc", shortCode).update();
	}
}
