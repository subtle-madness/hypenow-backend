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

	/** TEST-CODE 개통·리셋 — 1회용 코드(signup_codes)라 이미 소진됐어도 미사용 상태로 되돌린다. */
	public static void enableSignupCode(JdbcClient jdbcClient) {
		jdbcClient.sql("""
				INSERT INTO app.signup_codes (code, channel) VALUES (:code, 'TEST')
				ON CONFLICT (code) DO UPDATE SET used_by = NULL, used_at = NULL""")
				.param("code", SIGNUP_CODE)
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

	/**
	 * 이메일 인증 우회 시드 — 가입 전 강제(설계 2026-07-18) 이후 signup 전에 필요.
	 * send/confirm 왕복 없이 verified 행을 직접 심는다(코드 해시는 무관 값).
	 */
	public static void markEmailVerified(JdbcClient jdbcClient, String email) {
		jdbcClient.sql("""
				INSERT INTO app.email_verifications (email, code_hash, code_expires_at, verified_at)
				VALUES (lower(trim(:email)), 'seeded', now(), now())
				ON CONFLICT (email) DO UPDATE SET verified_at = now()""")
				.param("email", email)
				.update();
	}

	/** 가입(자동 로그인) 후 hypenow-session 쿠키 반환 — 이메일 인증·가입 코드 시드 포함(코드는 1회용이라 매번 리셋). */
	public static Cookie signUp(MockMvc mockMvc, JdbcClient jdbcClient, String email) throws Exception {
		enableSignupCode(jdbcClient);
		markEmailVerified(jdbcClient, email);
		MvcResult result = mockMvc.perform(post("/v1/auth/signup").with(csrf())
						.contentType(MediaType.APPLICATION_JSON).content(signupBody(email)))
				.andExpect(status().isCreated())
				.andReturn();
		Cookie session = result.getResponse().getCookie("hypenow-session");
		assertThat(session).isNotNull();
		return session;
	}
}
