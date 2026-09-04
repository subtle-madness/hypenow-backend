package com.celfit.was;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.celfit.was.crypto.FieldCipher;
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
 * 전역 크롤링 비용 API 실 DB 통합 검증(설계 2026-08-13) — 인가, 세 소스 합산, 0 구간의 행 유지,
 * 단가 PUT 반영을 실 스택으로 고정한다. KST 경계·열화 규칙의 순수 로직은
 * AdminCrawlingCostSummaryServiceTest가 고정 Clock으로 커버.
 *
 * <p>Clock을 2026-08-13 10:00 KST로 고정한다 — 시드 날짜가 달력에 무관하게 결정적이 되게.
 * crawl_call_daily는 analytics 모듈 Flyway 소관이라 was 테스트 DB에 없다 — 여기서 만든다.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {"monitoring.enabled=true", "monitoring.digest.weekly-cron=-",
		"monitoring.digest.weekly-catchup-cron=-", "monitoring.recover.cron=-",
		"was.rate-limit.per-minute=100"})
class AdminCrawlingCostSummaryIntegrationTest extends IntegrationTest {

	@DynamicPropertySource
	static void monitoringDatasource(DynamicPropertyRegistry registry) {
		registry.add("monitoring.datasource.url", POSTGRES::getJdbcUrl);
		registry.add("monitoring.datasource.username", POSTGRES::getUsername);
		registry.add("monitoring.datasource.password", POSTGRES::getPassword);
	}

	/** 고정 시각: 2026-08-13 10:00 KST (= 01:00Z). 오늘=08-13, 이번 달 시작=08-01. */
	@TestConfiguration
	static class FixedClockConfig {
		@Bean
		@Primary
		Clock fixedClock() {
			return Clock.fixed(Instant.parse("2026-08-13T01:00:00Z"), ZoneOffset.UTC);
		}
	}

	private static final String PASSWORD = "Passw0rd!";
	private static final String ADMIN_EMAIL = "admin-cost@test.io";
	private static final String USER_EMAIL = "user-cost@test.io";

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

	private Cookie adminSession;

	@BeforeEach
	void setUp() throws Exception {
		try (Connection conn = dataSource.getConnection()) {
			ScriptUtils.executeSqlScript(conn, new ClassPathResource("monitoring-brand-schema.sql"));
		}
		jdbcClient.sql("TRUNCATE brand_call_count").update();
		jdbcClient.sql("TRUNCATE target_call_count").update();
		jdbcClient.sql("DROP TABLE IF EXISTS crawl_call_daily").update();
		jdbcClient.sql("""
				CREATE TABLE crawl_call_daily (job text NOT NULL, called_on date NOT NULL,
				    calls bigint NOT NULL, PRIMARY KEY (job, called_on))
				""").update();
		// 자식부터 정리(FK) — saved_*는 users FK에 ON DELETE CASCADE가 없어, 앞서 돈 클래스가
		// 남긴 시드가 있으면 users 삭제가 FK 위반으로 깨진다(컨테이너는 JVM 전체 공유).
		jdbcClient.sql("DELETE FROM app.saved_contents").update();
		jdbcClient.sql("DELETE FROM app.saved_influencers").update();
		jdbcClient.sql("DELETE FROM app.users").update();
		jdbcClient.sql("""
				INSERT INTO app.app_setting (key, value) VALUES ('crawling.unit-price-usd', '0.0006')
				ON CONFLICT (key) DO UPDATE SET value = '0.0006'
				""").update();

		insertUser(ADMIN_EMAIL, "ADMIN");
		insertUser(USER_EMAIL, "USER");
		adminSession = login(ADMIN_EMAIL);
	}

	@Test
	void 미인증은_401이고_일반_유저는_403이다() throws Exception {
		mockMvc.perform(get("/v1/admin/crawling-cost/summary"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));

		mockMvc.perform(get("/v1/admin/crawling-cost/summary").cookie(login(USER_EMAIL)))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
	}

	@Test
	void 데이터가_없어도_200과_고정_7행을_돌려준다() throws Exception {
		mockMvc.perform(get("/v1/admin/crawling-cost/summary").cookie(adminSession))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data.totals.totalCalls").value(0))
				.andExpect(jsonPath("$.data.breakdown.length()").value(7))
				.andExpect(jsonPath("$.data.breakdown[0].key").value("BRAND_MONITORING"))
				.andExpect(jsonPath("$.data.breakdown[0].label").value("브랜드 태그 모니터링"))
				.andExpect(jsonPath("$.data.unitPriceUsd").value(0.0006))
				.andExpect(jsonPath("$.data.sources[?(@.key == 'MONITORING')].available").value(true));
	}

	@Test
	void 세_소스를_합산하고_구간별로_쪼갠다() throws Exception {
		// 브랜드: 오늘 10 + 이달 90 + 지난달 400. 서로 다른 브랜드가 같은 날 쌓여도 합쳐진다.
		jdbcClient.sql("""
				INSERT INTO brand_call_count VALUES
				 (1, date '2026-08-13', 6), (2, date '2026-08-13', 4),
				 (1, date '2026-08-01', 90), (1, date '2026-07-31', 400)
				""").update();
		// 캠페인: 오늘 5.
		jdbcClient.sql("INSERT INTO target_call_count VALUES (100, date '2026-08-13', 5)").update();
		// 크롤러: COLLECT 오늘 100, REELS 지난달 1000.
		jdbcClient.sql("""
				INSERT INTO crawl_call_daily VALUES
				 ('COLLECT', date '2026-08-13', 100), ('REELS', date '2026-07-20', 1000)
				""").update();

		mockMvc.perform(get("/v1/admin/crawling-cost/summary").cookie(adminSession))
				.andExpect(status().isOk())
				// 전체 500+5+100+1000, 이달 100+5+100, 오늘 10+5+100
				.andExpect(jsonPath("$.data.totals.totalCalls").value(1605))
				.andExpect(jsonPath("$.data.totals.monthCalls").value(205))
				.andExpect(jsonPath("$.data.totals.todayCalls").value(115))
				// 1605 × 0.0006 = 0.9630
				.andExpect(jsonPath("$.data.totals.totalCostUsd").value(0.9630))
				.andExpect(jsonPath("$.data.breakdown[?(@.key == 'BRAND_MONITORING')].totalCalls").value(500))
				.andExpect(jsonPath("$.data.breakdown[?(@.key == 'CAMPAIGN_MONITORING')].todayCalls").value(5))
				.andExpect(jsonPath("$.data.breakdown[?(@.key == 'CRAWLER_COLLECT')].todayCalls").value(100))
				.andExpect(jsonPath("$.data.breakdown[?(@.key == 'CRAWLER_REELS')].totalCalls").value(1000))
				// 안 쓴 구간도 행이 남는다.
				.andExpect(jsonPath("$.data.breakdown[?(@.key == 'CRAWLER_DISCOVER')].totalCalls").value(0))
				.andExpect(jsonPath("$.data.sources[?(@.key == 'CRAWLER')].latestCallOn").value("2026-08-13"));
	}

	@Test
	void 단가_수정은_즉시_이_API에도_반영된다() throws Exception {
		jdbcClient.sql("INSERT INTO crawl_call_daily VALUES ('COLLECT', date '2026-08-13', 1000)").update();

		mockMvc.perform(put("/v1/admin/crawling-cost/unit-price").with(csrf()).cookie(adminSession)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"unitPriceUsd\":0.002}"))
				.andExpect(status().isOk());

		mockMvc.perform(get("/v1/admin/crawling-cost/summary").cookie(adminSession))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.unitPriceUsd").value(0.002))
				.andExpect(jsonPath("$.data.totals.totalCostUsd").value(2.000));
	}

	// --- 헬퍼 (AdminCrawlingUsageIntegrationTest와 동일 구현) ---

	private long insertUser(String email, String role) {
		long id = jdbcClient.sql("""
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
		PiiTestSeed.backfill(jdbcClient, fieldCipher);
		return id;
	}

	/** 세션 쿠키 이름은 hypenow-session이다(SESSION 아님 — 커스텀 쿠키 설정). */
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
}
