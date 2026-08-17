package com.celfit.was.v1.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.celfit.was.IntegrationTest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * GET /v1/me/entitlements 실 DB 검증(계획 Task 2, 설계 2026-08-17) — 계정은 DB 직접 시드 후
 * /v1/auth/login으로 세션 획득(AdminApiIntegrationTest 관용구). 오버라이드·ENTERPRISE 응답 형상은
 * 조직 배정 API(Task 3·4)가 갖춰진 뒤 별도 IT로 보강한다 — 여기는 무소속 폴백·인증 게이트만 다룬다.
 */
@AutoConfigureMockMvc
class MeEntitlementsIntegrationTest extends IntegrationTest {

	private static final String PASSWORD = "Passw0rd!";
	private static final String USER_EMAIL = "entitlements-me@test.io";

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcClient jdbcClient;

	@Autowired
	PasswordEncoder passwordEncoder;

	@BeforeEach
	void seedUser() {
		jdbcClient.sql("""
				INSERT INTO app.users (email, password_hash, role, name, user_type, agreed_terms,
				                       agreed_privacy, agreed_age14)
				VALUES (:email, :hash, 'USER', '테스트', 'brand', true, true, true)
				ON CONFLICT (email) DO UPDATE SET password_hash = :hash""")
				.param("email", USER_EMAIL)
				.param("hash", passwordEncoder.encode(PASSWORD))
				.update();
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

	@Test
	void 무소속_유저는_200이고_plan_free와_빈_배열이다() throws Exception {
		Cookie session = login(USER_EMAIL);

		mockMvc.perform(get("/v1/me/entitlements").cookie(session))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data.plan").value("free"))
				.andExpect(jsonPath("$.data.features").isArray())
				.andExpect(jsonPath("$.data.features.length()").value(0));
	}

	@Test
	void 비인증은_401이다() throws Exception {
		mockMvc.perform(get("/v1/me/entitlements"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
	}
}
