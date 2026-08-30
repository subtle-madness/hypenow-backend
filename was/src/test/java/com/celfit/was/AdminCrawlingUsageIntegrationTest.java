package com.celfit.was;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
 * 어드민 크롤링 비용 카드 API 2종 실 DB 통합 검증(2026-08-12 프론트 요청서) — 인가(비어드민 403·
 * 미인증 401), 이력 없는 유저 200+0, 연결 기간 귀속 집계, PUT 반영·유효성을 실 스택으로 고정한다.
 * KST 자정·월초 경계의 순수 로직은 AdminCrawlingUsageServiceTest가 고정 Clock 쌍으로 커버.
 *
 * <p>Clock을 2026-08-20 10:00 KST로 고정한다 — 시드 날짜가 달력에 무관하게 결정적이 되게.
 * 링크 created_at은 과거로 UPDATE한다(고정 Clock과 달리 DB now()는 실제 시각이라, 실행일이
 * 고정 시점보다 미래면 "연결 전 콜"로 오판된다).
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {"monitoring.enabled=true", "monitoring.digest.weekly-cron=-",
		"monitoring.digest.weekly-catchup-cron=-", "monitoring.recover.cron=-",
		"was.rate-limit.per-minute=100"})
class AdminCrawlingUsageIntegrationTest extends IntegrationTest {

	@DynamicPropertySource
	static void monitoringDatasource(DynamicPropertyRegistry registry) {
		registry.add("monitoring.datasource.url", POSTGRES::getJdbcUrl);
		registry.add("monitoring.datasource.username", POSTGRES::getUsername);
		registry.add("monitoring.datasource.password", POSTGRES::getPassword);
	}

	/** 고정 시각: 2026-08-20 10:00 KST (= 01:00Z). 오늘=08-20, 이번 달 시작=08-01. */
	@TestConfiguration
	static class FixedClockConfig {
		@Bean
		@Primary
		Clock fixedClock() {
			return Clock.fixed(Instant.parse("2026-08-20T01:00:00Z"), ZoneOffset.UTC);
		}
	}

	private static final String PASSWORD = "Passw0rd!";
	private static final String ADMIN_EMAIL = "admin-crawling@test.io";
	private static final String USER_EMAIL = "user-crawling@test.io";

	@Autowired
	MockMvc mockMvc;
	@Autowired
	JdbcClient jdbcClient;
	@Autowired
	DataSource dataSource;
	@Autowired
	PasswordEncoder passwordEncoder;

	private Cookie adminSession;
	private long targetUserId;

	@BeforeEach
	void setUp() throws Exception {
		try (Connection conn = dataSource.getConnection()) {
			ScriptUtils.executeSqlScript(conn, new ClassPathResource("monitoring-brand-schema.sql"));
		}
		jdbcClient.sql("TRUNCATE brand_call_count").update();
		jdbcClient.sql("TRUNCATE target_call_count").update();
		jdbcClient.sql("DELETE FROM brand_tagged_post").update();
		jdbcClient.sql("DELETE FROM brand_account").update();
		jdbcClient.sql("DELETE FROM app.brand_monitorings").update();
		// 자식부터 정리(FK) — saved_*는 users FK에 ON DELETE CASCADE가 없어, 앞서 돈 클래스가
		// 남긴 시드가 있으면 users 삭제가 FK 위반으로 깨진다(컨테이너는 JVM 전체 공유).
		jdbcClient.sql("DELETE FROM app.saved_contents").update();
		jdbcClient.sql("DELETE FROM app.saved_influencers").update();
		jdbcClient.sql("DELETE FROM app.users").update();
		// PUT 테스트가 전역 단가를 바꾼다 — 매 테스트 시드값으로 복원.
		jdbcClient.sql("""
				INSERT INTO app.app_setting (key, value) VALUES ('crawling.unit-price-usd', '0.0006')
				ON CONFLICT (key) DO UPDATE SET value = '0.0006'
				""").update();

		insertUser(ADMIN_EMAIL, "ADMIN");
		targetUserId = insertUser(USER_EMAIL, "USER");
		adminSession = login(ADMIN_EMAIL);
	}

	// --- 인가 ---

	@Test
	void 미인증은_401이고_일반_유저는_403이다() throws Exception {
		mockMvc.perform(get("/v1/admin/users/%d/crawling-usage".formatted(targetUserId)))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));

		Cookie userSession = login(USER_EMAIL);
		mockMvc.perform(get("/v1/admin/users/%d/crawling-usage".formatted(targetUserId)).cookie(userSession))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
		mockMvc.perform(put("/v1/admin/crawling-cost/unit-price").with(csrf()).cookie(userSession)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"unitPriceUsd\":0.002}"))
				.andExpect(status().isForbidden());
	}

	// --- GET 집계 ---

	@Test
	void 크롤링_이력이_없는_유저는_200과_전부_0이다() throws Exception {
		mockMvc.perform(get("/v1/admin/users/%d/crawling-usage".formatted(targetUserId)).cookie(adminSession))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data.totalCalls").value(0))
				.andExpect(jsonPath("$.data.monthCalls").value(0))
				.andExpect(jsonPath("$.data.todayCalls").value(0))
				.andExpect(jsonPath("$.data.unitPriceUsd").value(0.0006));
	}

	@Test
	void 존재하지_않는_유저와_비숫자_id도_404가_아니라_200에_0이다() throws Exception {
		mockMvc.perform(get("/v1/admin/users/999999/crawling-usage").cookie(adminSession))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.totalCalls").value(0));
		mockMvc.perform(get("/v1/admin/users/abc/crawling-usage").cookie(adminSession))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.totalCalls").value(0));
	}

	@Test
	void 연결_기간의_콜만_KST_구간별로_합산한다() throws Exception {
		// 브랜드 A: 활성 연결(1월부터) — 오늘 12 + 이달 초 288 + 지난달 말 2241 + 연결 전 99(제외).
		long brandA = insertBrand("brand_a");
		linkBrand(targetUserId, brandA, "2026-01-10T00:00:00+09:00", null);
		insertCalls(brandA, "2026-08-20", 12);
		insertCalls(brandA, "2026-08-05", 288);
		insertCalls(brandA, "2026-07-31", 2241);
		insertCalls(brandA, "2025-12-31", 99);
		// 브랜드 B: 3~5월만 연결했다 해제 — 기간 안 100은 포함, 해제 후 50은 제외.
		long brandB = insertBrand("brand_b");
		linkBrand(targetUserId, brandB, "2026-03-01T00:00:00+09:00", "2026-05-31T23:00:00+09:00");
		insertCalls(brandB, "2026-04-15", 100);
		insertCalls(brandB, "2026-08-19", 50);

		mockMvc.perform(get("/v1/admin/users/%d/crawling-usage".formatted(targetUserId)).cookie(adminSession))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.totalCalls").value(2641))
				.andExpect(jsonPath("$.data.monthCalls").value(300))
				.andExpect(jsonPath("$.data.todayCalls").value(12))
				.andExpect(jsonPath("$.data.unitPriceUsd").value(0.0006));
	}

	@Test
	void 캠페인_콘텐츠_모니터링_콜도_합산된다() throws Exception {
		// 브랜드 연결 없이 target_call_count(캠페인·콘텐츠 몫, 유저 키)만 있는 유저 — 2026-08-12 범위 확장.
		insertTargetCalls(targetUserId, "2026-08-20", 4);
		insertTargetCalls(targetUserId, "2026-08-11", 90);
		insertTargetCalls(targetUserId, "2026-07-31", 6);
		insertTargetCalls(targetUserId + 1, "2026-08-20", 999);   // 남의 몫 — 미합산

		mockMvc.perform(get("/v1/admin/users/%d/crawling-usage".formatted(targetUserId)).cookie(adminSession))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.totalCalls").value(100))
				.andExpect(jsonPath("$.data.monthCalls").value(94))
				.andExpect(jsonPath("$.data.todayCalls").value(4));

		// 브랜드 몫과 공존하면 단순 합.
		long brand = insertBrand("brand_mix");
		linkBrand(targetUserId, brand, "2026-01-10T00:00:00+09:00", null);
		insertCalls(brand, "2026-08-20", 1);

		mockMvc.perform(get("/v1/admin/users/%d/crawling-usage".formatted(targetUserId)).cookie(adminSession))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.totalCalls").value(101))
				.andExpect(jsonPath("$.data.todayCalls").value(5));
	}

	// --- PUT 단가 ---

	@Test
	void 단가_수정은_즉시_이후_GET에_반영된다() throws Exception {
		mockMvc.perform(put("/v1/admin/crawling-cost/unit-price").with(csrf()).cookie(adminSession)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"unitPriceUsd\":0.0025}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true));

		mockMvc.perform(get("/v1/admin/users/%d/crawling-usage".formatted(targetUserId)).cookie(adminSession))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.unitPriceUsd").value(0.0025));
	}

	@Test
	void 단가_유효성_음수_누락_비숫자는_VALIDATION_FAILED다() throws Exception {
		String[] invalidBodies = {
				"{\"unitPriceUsd\":-0.001}",     // 음수
				"{}",                             // 누락
				"{\"unitPriceUsd\":null}",       // 명시적 null
				"{\"unitPriceUsd\":\"NaN\"}",    // NaN 성격 문자열 — JSON 숫자로 표현 불가
				"{\"unitPriceUsd\":\"abc\"}",    // 비숫자 문자열
		};
		for (String body : invalidBodies) {
			mockMvc.perform(put("/v1/admin/crawling-cost/unit-price").with(csrf()).cookie(adminSession)
							.contentType(MediaType.APPLICATION_JSON)
							.content(body))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
		}

		// 거부된 요청은 저장값을 건드리지 않는다.
		mockMvc.perform(get("/v1/admin/users/%d/crawling-usage".formatted(targetUserId)).cookie(adminSession))
				.andExpect(jsonPath("$.data.unitPriceUsd").value(0.0006));
	}

	// --- 헬퍼 ---

	private long insertUser(String email, String role) {
		return jdbcClient.sql("""
				INSERT INTO app.users (email, password_hash, role, name, user_type,
				                       agreed_terms, agreed_privacy, agreed_age14)
				VALUES (:email, :hash, :role, '테스터', 'brand', true, true, true)
				RETURNING id
				""")
				.param("email", email)
				.param("hash", passwordEncoder.encode(PASSWORD))
				.param("role", role)
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

	private long insertBrand(String username) {
		return jdbcClient.sql("""
				INSERT INTO brand_account (username, ig_user_id) VALUES (:username, '111')
				RETURNING id
				""")
				.param("username", username)
				.query(Long.class)
				.single();
	}

	private void linkBrand(long userId, long brandId, String createdAt, String deletedAt) {
		jdbcClient.sql("""
				INSERT INTO app.brand_monitorings (user_id, brand_id, username, created_at, deleted_at)
				VALUES (:userId, :brandId, 'brand', :createdAt::timestamptz, :deletedAt::timestamptz)
				""")
				.param("userId", userId)
				.param("brandId", brandId)
				.param("createdAt", createdAt)
				.param("deletedAt", deletedAt)
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

	private void insertTargetCalls(long userId, String calledOn, long calls) {
		jdbcClient.sql("""
				INSERT INTO target_call_count (user_id, called_on, calls)
				VALUES (:userId, :calledOn::date, :calls)
				""")
				.param("userId", userId)
				.param("calledOn", calledOn)
				.param("calls", calls)
				.update();
	}
}
