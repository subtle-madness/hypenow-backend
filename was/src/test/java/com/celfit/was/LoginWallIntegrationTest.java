package com.celfit.was;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 로그인 월 계약(설계 07-17) — 기본 잠금 화이트리스트를 실 필터체인·실 DB로 검증한다.
 * 익명 요청은 AuthorizationFilter에서 끊기므로 분석 테이블 부재(테스트 컨테이너는 app 스키마만)와 무관.
 */
@AutoConfigureMockMvc
class LoginWallIntegrationTest extends IntegrationTest {

	private static final String SIGNUP_BODY = """
			{"signupCode":"WALL-TEST","email":"wall@example.com","password":"Passw0rd!","name":"김우민",
			 "userType":"brand","signupRoute":"portal_search","phoneCountryCode":"+82",
			 "phoneNumber":"010-1234-5678","companyName":"하이프나우","companySize":"2-10",
			 "industry":"beauty","jobTitle":"staff",
			 "agreedTerms":true,"agreedPrivacy":true,"agreedAge14":true,"agreedMarketing":false}""";

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcClient jdbcClient;

	@Test
	void 잠긴_v1_읽기는_익명_401_envelope다() throws Exception {
		mockMvc.perform(get("/v1/contents?startDate=2026-06-01&endDate=2026-07-17"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"))
				.andExpect(jsonPath("$.error.message").value("로그인이 필요합니다."));
	}

	@Test
	void 내부_페이지와_구_api_표면도_익명_401이다() throws Exception {
		mockMvc.perform(get("/")).andExpect(status().isUnauthorized());
		mockMvc.perform(get("/dashboard")).andExpect(status().isUnauthorized());
		mockMvc.perform(get("/api/contents")).andExpect(status().isUnauthorized());
		mockMvc.perform(get("/api/auth/login").contentType(MediaType.APPLICATION_JSON))
				.andExpect(status().isUnauthorized()); // 레거시 auth도 잠금 — 인증 입구는 /v1뿐
		mockMvc.perform(get("/profile-images/x.png")).andExpect(status().isUnauthorized());
	}

	@Test
	void 열린_경로는_익명으로_통과한다() throws Exception {
		mockMvc.perform(get("/health")).andExpect(status().isOk());

		// 게이트 이벤트 — 익명 + CSRF 면제 그대로(스펙 6.19)
		mockMvc.perform(post("/v1/events/gate").contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"eventType":"gate_view","payload":{"from":"wall-test"}}"""))
				.andExpect(status().isNoContent());

		// 로그인 — 월(401 envelope)이 아니라 인증 로직(INVALID_CREDENTIALS)에 도달했다는 증거
		mockMvc.perform(post("/v1/auth/login").with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"email":"nobody@example.com","password":"Wrong123!"}"""))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"));
	}

	@Test
	void 로그인한_세션은_월을_통과한다() throws Exception {
		jdbcClient.sql("UPDATE app.app_setting SET value = 'WALL-TEST' WHERE key = 'signup.code'").update();
		try {
			MvcResult signup = mockMvc.perform(post("/v1/auth/signup").with(csrf())
							.contentType(MediaType.APPLICATION_JSON).content(SIGNUP_BODY))
					.andExpect(status().isCreated())
					.andReturn();
			Cookie session = signup.getResponse().getCookie("hypenow-session");
			assertThat(session).isNotNull();

			// 분석 테이블이 없는 컨테이너라 200은 불가 — 월(401)에 안 막힌 것만 확인
			MvcResult read = mockMvc.perform(
					get("/v1/contents?startDate=2026-06-01&endDate=2026-07-17").cookie(session)).andReturn();
			assertThat(read.getResponse().getStatus()).isNotEqualTo(401);
		} finally {
			jdbcClient.sql("UPDATE app.app_setting SET value = '' WHERE key = 'signup.code'").update();
			jdbcClient.sql("DELETE FROM app.users WHERE email = 'wall@example.com'").update();
		}
	}
}
