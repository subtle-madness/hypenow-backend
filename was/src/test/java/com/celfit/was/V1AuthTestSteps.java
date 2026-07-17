package com.celfit.was;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * v1 가입 테스트 스텝 — 로그인 월(07-17) 이후 세션이 필요한 통합 테스트 공용.
 * 레거시 /api/auth 경유(구 AuthFlow 방식)는 월에 잠겨 못 쓴다. 가입은 자동 로그인이라
 * signUp 한 번으로 세션 쿠키까지 얻는다. 각 테스트는 먼저 enableSignupCode로 코드를 개통할 것
 * (V6 시드는 빈 값 = fail-closed 차단 상태).
 */
public final class V1AuthTestSteps {

	public static final String SIGNUP_CODE = "TEST-CODE";
	public static final String PASSWORD = "Passw0rd!";

	private V1AuthTestSteps() {
	}

	public static void enableSignupCode(JdbcClient jdbcClient) {
		jdbcClient.sql("UPDATE app.app_setting SET value = :value WHERE key = 'signup.code'")
				.param("value", SIGNUP_CODE)
				.update();
	}

	public static String signupBody(String email) {
		return """
				{"signupCode":"%s","email":"%s","password":"%s","name":"김우민",
				 "userType":"brand","signupRoute":"portal_search","phoneCountryCode":"+82",
				 "phoneNumber":"010-1234-5678","companyName":"하이프나우","companySize":"2-10",
				 "industry":"beauty","jobTitle":"staff",
				 "agreedTerms":true,"agreedPrivacy":true,"agreedAge14":true,"agreedMarketing":false}"""
				.formatted(SIGNUP_CODE, email, PASSWORD);
	}

	/** 가입(자동 로그인) 후 hypenow-session 쿠키 반환. */
	public static Cookie signUp(MockMvc mockMvc, String email) throws Exception {
		MvcResult result = mockMvc.perform(post("/v1/auth/signup").with(csrf())
						.contentType(MediaType.APPLICATION_JSON).content(signupBody(email)))
				.andExpect(status().isCreated())
				.andReturn();
		Cookie session = result.getResponse().getCookie("hypenow-session");
		assertThat(session).isNotNull();
		return session;
	}
}
