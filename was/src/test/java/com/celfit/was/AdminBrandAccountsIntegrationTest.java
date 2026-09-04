package com.celfit.was;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.celfit.was.crypto.FieldCipher;
import com.celfit.was.v1.admin.AdminBrandAccountService;
import jakarta.servlet.http.Cookie;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
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
 * 어드민 "등록된 브랜드 목록" API 실 DB 통합 검증(2026-09-03) — 인가(비어드민 403·미인증 401),
 * 연결·유저·브랜드 계정·게시물·콜 집계를 실 스택으로 조립해 응답 필드·q 필터·정렬·잘못된 sort 400을
 * 고정한다. 정렬 6종 전체·타이브레이크 등 순수 로직은 AdminBrandAccountServiceTest가 고정 Clock으로
 * 커버(같은 관례는 AdminCrawlingUsageIntegrationTest 참조).
 *
 * <p>Clock을 2026-08-20 10:00 KST로 고정한다 — 월 경계(이번 달 시작 08-01)가 달력에 무관하게
 * 결정적이 되게.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {"monitoring.enabled=true", "monitoring.digest.weekly-cron=-",
		"monitoring.digest.weekly-catchup-cron=-", "monitoring.recover.cron=-",
		"was.rate-limit.per-minute=100"})
class AdminBrandAccountsIntegrationTest extends IntegrationTest {

	@DynamicPropertySource
	static void monitoringDatasource(DynamicPropertyRegistry registry) {
		registry.add("monitoring.datasource.url", POSTGRES::getJdbcUrl);
		registry.add("monitoring.datasource.username", POSTGRES::getUsername);
		registry.add("monitoring.datasource.password", POSTGRES::getPassword);
	}

	/** 고정 시각: 2026-08-20 10:00 KST(= 01:00Z). 이번 달 시작 = 08-01. */
	@TestConfiguration
	static class FixedClockConfig {
		@Bean
		@Primary
		Clock fixedClock() {
			return Clock.fixed(Instant.parse("2026-08-20T01:00:00Z"), ZoneOffset.UTC);
		}
	}

	private static final String PASSWORD = "Passw0rd!";
	private static final String ADMIN_EMAIL = "admin-brand-accounts@test.io";
	private static final String USER_EMAIL = "user-brand-accounts@test.io";

	@Autowired
	MockMvc mockMvc;
	@Autowired
	JdbcClient jdbcClient;
	@Autowired
	DataSource dataSource;
	@Autowired
	PasswordEncoder passwordEncoder;
	@Autowired
	FieldCipher fieldCipher;
	@Autowired
	AdminBrandAccountService adminBrandAccountService;

	private Cookie adminSession;
	private long targetUserId;

	@BeforeEach
	void setUp() throws Exception {
		// 조립 결과가 60초 캐시된다(2026-09-04, monitoring-ro 풀 경합 대응) — Spring 컨텍스트가 테스트
		// 메서드 간 재사용되므로, 이전 테스트가 채운 캐시를 이 테스트가 그대로 보지 않도록 매번 비운다.
		adminBrandAccountService.invalidateCacheForTests();
		try (Connection conn = dataSource.getConnection()) {
			ScriptUtils.executeSqlScript(conn, new ClassPathResource("monitoring-brand-schema.sql"));
		}
		jdbcClient.sql("TRUNCATE brand_call_count").update();
		jdbcClient.sql("DELETE FROM brand_tagged_post").update();
		jdbcClient.sql("DELETE FROM brand_account").update();
		jdbcClient.sql("DELETE FROM app.brand_monitorings").update();
		jdbcClient.sql("DELETE FROM app.saved_contents").update();
		jdbcClient.sql("DELETE FROM app.saved_influencers").update();
		jdbcClient.sql("DELETE FROM app.users").update();

		insertUser(ADMIN_EMAIL, "ADMIN", "관리자", "");
		targetUserId = insertUser(USER_EMAIL, "USER", "김유저", "하입나우");
		adminSession = login(ADMIN_EMAIL);
	}

	// --- 인가 ---

	@Test
	void 미인증은_401이고_일반_유저는_403이다() throws Exception {
		mockMvc.perform(get("/v1/admin/brand-monitoring/accounts"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));

		Cookie userSession = login(USER_EMAIL);
		mockMvc.perform(get("/v1/admin/brand-monitoring/accounts").cookie(userSession))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
	}

	// --- 행복 경로 ---

	@Test
	void 어드민_조회는_연결_계정_게시물_콜_집계를_한_행에_담는다() throws Exception {
		long brand = insertBrand("beauty_official");
		linkBrand(targetUserId, brand, "beauty_official", "own", 6, "2026-08-10T09:00:00+09:00");
		markSwept(brand, "2026-08-15");
		insertPost(brand, "sc1", "2026-08-01T00:00:00Z");
		insertPost(brand, "sc2", "2026-08-02T00:00:00Z");
		insertPost(brand, "sc3", "2026-08-03T00:00:00Z");
		insertCalls(brand, "2026-07-31", 100);   // 이번 달 이전 — total에만
		insertCalls(brand, "2026-08-05", 20);    // 이번 달 — total·month 둘 다

		mockMvc.perform(get("/v1/admin/brand-monitoring/accounts").cookie(adminSession))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data.length()").value(1))
				.andExpect(jsonPath("$.data[0].accountId").value(String.valueOf(brand)))
				.andExpect(jsonPath("$.data[0].username").value("beauty_official"))
				.andExpect(jsonPath("$.data[0].mode").value("own"))
				.andExpect(jsonPath("$.data[0].user.id").value(targetUserId))
				.andExpect(jsonPath("$.data[0].user.email").value(USER_EMAIL))
				.andExpect(jsonPath("$.data[0].user.name").value("김유저"))
				.andExpect(jsonPath("$.data[0].user.orgName").value("하입나우"))
				.andExpect(jsonPath("$.data[0].postCount").value(3))
				.andExpect(jsonPath("$.data[0].crawlingCalls.total").value(120))
				.andExpect(jsonPath("$.data[0].crawlingCalls.month").value(20))
				.andExpect(jsonPath("$.data[0].collectionStatus").value("ready"))
				.andExpect(jsonPath("$.data[0].collectionMonths").value(6))
				.andExpect(jsonPath("$.data[0].registeredAt").value("2026-08-10T09:00:00+09:00"))
				.andExpect(jsonPath("$.data[0].lastCollectedAt").exists())
				.andExpect(jsonPath("$.meta.total").value(1))
				.andExpect(jsonPath("$.meta.limit").value(20))
				.andExpect(jsonPath("$.meta.offset").value(0));
	}

	@Test
	void 이름_없는_유저는_등록_시_스냅샷_이름이_아니라_계정_최신_username을_쓴다() throws Exception {
		long brand = insertBrand("current_username");
		linkBrand(targetUserId, brand, "old_username_at_registration", "competitor", 12,
				"2026-08-01T00:00:00+09:00");

		mockMvc.perform(get("/v1/admin/brand-monitoring/accounts").cookie(adminSession))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data[0].username").value("current_username"))
				.andExpect(jsonPath("$.data[0].mode").value("competitor"))
				.andExpect(jsonPath("$.data[0].collectionStatus").value("collecting"))
				.andExpect(jsonPath("$.data[0].postCount").value(0))
				.andExpect(jsonPath("$.data[0].crawlingCalls.total").value(0))
				.andExpect(jsonPath("$.data[0].backfillCompletedAt").doesNotExist());
	}

	@Test
	void q는_계정명과_이메일_대소문자_무시_부분일치다() throws Exception {
		long brandA = insertBrand("skincare_lab");
		linkBrand(targetUserId, brandA, "skincare_lab", "own", 12, "2026-08-01T00:00:00+09:00");
		long otherUserId = insertUser("marketer@brandco.io", "USER", "", "");
		long brandB = insertBrand("makeup_studio");
		linkBrand(otherUserId, brandB, "makeup_studio", "own", 12, "2026-08-02T00:00:00+09:00");

		mockMvc.perform(get("/v1/admin/brand-monitoring/accounts").cookie(adminSession).param("q", "SKINCARE"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.length()").value(1))
				.andExpect(jsonPath("$.data[0].username").value("skincare_lab"));

		mockMvc.perform(get("/v1/admin/brand-monitoring/accounts").cookie(adminSession).param("q", "brandco"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.length()").value(1))
				.andExpect(jsonPath("$.data[0].username").value("makeup_studio"));
	}

	@Test
	void 정렬_registeredAt_desc가_기본이고_asc_지정도_동작한다() throws Exception {
		long brandOld = insertBrand("brand_old");
		linkBrand(targetUserId, brandOld, "brand_old", "own", 12, "2026-08-01T00:00:00+09:00");
		long otherUserId = insertUser("second@test.io", "USER", "", "");
		long brandNew = insertBrand("brand_new");
		linkBrand(otherUserId, brandNew, "brand_new", "own", 12, "2026-08-15T00:00:00+09:00");

		mockMvc.perform(get("/v1/admin/brand-monitoring/accounts").cookie(adminSession))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data[0].username").value("brand_new"))
				.andExpect(jsonPath("$.data[1].username").value("brand_old"));

		mockMvc.perform(get("/v1/admin/brand-monitoring/accounts").cookie(adminSession)
						.param("sort", "registeredAt:asc"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data[0].username").value("brand_old"))
				.andExpect(jsonPath("$.data[1].username").value("brand_new"));
	}

	@Test
	void 잘못된_sort는_400이다() throws Exception {
		mockMvc.perform(get("/v1/admin/brand-monitoring/accounts").cookie(adminSession)
						.param("sort", "nope:asc"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

		mockMvc.perform(get("/v1/admin/brand-monitoring/accounts").cookie(adminSession)
						.param("sort", "registeredAt:sideways"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
	}

	@Test
	void offset이_있으면_page보다_우선한다() throws Exception {
		for (int i = 0; i < 3; i++) {
			long brand = insertBrand("brand_page_" + i);
			long userId = insertUser("page-user-" + i + "@test.io", "USER", "", "");
			linkBrand(userId, brand, "brand_page_" + i, "own", 12, "2026-08-0%dT00:00:00+09:00".formatted(i + 1));
		}
		// offset=1&page=5 → offset이 이겨야 하므로 limit=1일 때 전체 3건 중 2번째 행(오프셋 1)만 나온다.
		mockMvc.perform(get("/v1/admin/brand-monitoring/accounts").cookie(adminSession)
						.param("offset", "1").param("page", "5").param("limit", "1")
						.param("sort", "registeredAt:asc"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.length()").value(1))
				.andExpect(jsonPath("$.meta.offset").value(1))
				.andExpect(jsonPath("$.meta.limit").value(1))
				.andExpect(jsonPath("$.meta.total").value(3))
				.andExpect(jsonPath("$.data[0].username").value("brand_page_1"));
	}

	// --- 헬퍼 ---

	private long insertUser(String email, String role, String name, String companyName) {
		long id = jdbcClient.sql("""
				INSERT INTO app.users (email, password_hash, role, name, user_type, company_name,
				                       agreed_terms, agreed_privacy, agreed_age14)
				VALUES (:email, :hash, :role, :name, 'brand', :companyName, true, true, true)
				RETURNING id
				""")
				.param("email", email)
				.param("hash", passwordEncoder.encode(PASSWORD))
				.param("role", role)
				.param("name", name)
				.param("companyName", companyName)
				.query(Long.class)
				.single();
		PiiTestSeed.backfill(jdbcClient, fieldCipher);
		return id;
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

	private long insertBrand(String username) {
		return jdbcClient.sql("""
				INSERT INTO brand_account (username, ig_user_id) VALUES (:username, '111')
				RETURNING id
				""")
				.param("username", username)
				.query(Long.class)
				.single();
	}

	private void markSwept(long brandId, String lastSweptOn) {
		jdbcClient.sql("""
				UPDATE brand_account SET last_swept_on = :lastSweptOn::date, last_swept_at = now()
				WHERE id = :id
				""")
				.param("lastSweptOn", lastSweptOn)
				.param("id", brandId)
				.update();
	}

	private void insertPost(long brandId, String shortCode, String takenAt) {
		jdbcClient.sql("""
				INSERT INTO brand_tagged_post (brand_id, short_code, author_username, taken_at)
				VALUES (:brandId, :shortCode, 'author', :takenAt::timestamptz)
				""")
				.param("brandId", brandId)
				.param("shortCode", shortCode)
				.param("takenAt", takenAt)
				.update();
	}

	private void linkBrand(long userId, long brandId, String username, String accountType, int collectionMonths,
			String createdAt) {
		jdbcClient.sql("""
				INSERT INTO app.brand_monitorings (user_id, brand_id, username, account_type, collection_months,
				                                    created_at)
				VALUES (:userId, :brandId, :username, :accountType, :collectionMonths, :createdAt::timestamptz)
				""")
				.param("userId", userId)
				.param("brandId", brandId)
				.param("username", username)
				.param("accountType", accountType)
				.param("collectionMonths", collectionMonths)
				.param("createdAt", createdAt)
				.update();
	}

	private void insertCalls(long brandId, String calledOn, long calls) {
		jdbcClient.sql("""
				INSERT INTO brand_call_count (brand_id, called_on, calls)
				VALUES (:brandId, :calledOn::date, :calls)
				""")
				.param("brandId", brandId)
				.param("calledOn", calledOn)
				.param("calls", calls)
				.update();
	}
}
