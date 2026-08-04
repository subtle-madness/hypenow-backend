package com.celfit.was;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 배치 1회용 가입 코드 계약(클로즈베타 설계 2026-07-19) — app.signup_codes 실 DB 검증.
 * 사전 검증(/v1/auth/signup-code/verify)과 가입 시 원자적 소진(used_by 선점)을 함께 커버한다.
 * 레이트리밋이 전역 싱글턴이라 테스트 메서드마다 고유 remoteAddr을 부여한다(이메일 인증 테스트 관용구).
 */
@AutoConfigureMockMvc
class SignupCodeIntegrationTest extends IntegrationTest {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcClient jdbcClient;

	private static final AtomicInteger IP_SEQUENCE = new AtomicInteger();

	private String testIp;

	@BeforeEach
	void setUp() {
		int seq = IP_SEQUENCE.incrementAndGet();
		testIp = "10.1.%d.%d".formatted(seq / 256, seq % 256);
	}

	private void seedCode(String code) {
		jdbcClient.sql("""
				INSERT INTO app.signup_codes (code, channel) VALUES (:code, 'TEST')
				ON CONFLICT (code) DO UPDATE SET used_by = NULL, used_at = NULL""")
				.param("code", code)
				.update();
	}

	private void seedSuperCode(String code) {
		jdbcClient.sql("""
				INSERT INTO app.signup_codes (code, channel, is_super) VALUES (:code, 'TEST', true)
				ON CONFLICT (code) DO UPDATE SET used_by = NULL, used_at = NULL, is_super = true""")
				.param("code", code)
				.update();
	}

	private org.springframework.test.web.servlet.ResultActions verify(String code) throws Exception {
		return mockMvc.perform(post("/v1/auth/signup-code/verify").with(csrf())
				.with(request -> {
					request.setRemoteAddr(testIp);
					return request;
				})
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"code\":\"%s\"}".formatted(code)));
	}

	private org.springframework.test.web.servlet.ResultActions signup(String code, String email) throws Exception {
		String body = """
				{"signupCode":"%s","email":"%s","password":"Passw0rd!","name":"김우민",
				 "userType":"brand","signupRoute":"portal_search","phoneCountryCode":"+82",
				 "phoneNumber":"010-1234-5678","companyName":"하이프나우","companySize":"2-10",
				 "industry":"beauty","jobTitle":"staff",
				 "agreedTerms":true,"agreedPrivacy":true,"agreedAge14":true,"agreedMarketing":false}"""
				.formatted(code, email);
		return mockMvc.perform(post("/v1/auth/signup").with(csrf())
				.with(request -> {
					request.setRemoteAddr(testIp);
					return request;
				})
				.contentType(MediaType.APPLICATION_JSON)
				.content(body));
	}

	@Test
	void 사전검증_미사용_코드는_200_valid_true() throws Exception {
		seedCode("THREADS-PRE1");
		verify("THREADS-PRE1")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data.valid").value(true));
	}

	@Test
	void 사전검증_없는_코드는_403_INVALID_SIGNUP_CODE() throws Exception {
		verify("THREADS-NOPE")
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.error.code").value("INVALID_SIGNUP_CODE"))
				.andExpect(jsonPath("$.error.message").value("존재하지 않거나 이미 사용된 코드입니다."));
	}

	@Test
	void 사전검증은_공백을_무시한다() throws Exception {
		seedCode("THREADS-TRIM");
		verify("  THREADS-TRIM  ")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.valid").value(true));
	}

	@Test
	void 가입하면_코드가_소진되고_used_by가_기록된다() throws Exception {
		seedCode("THREADS-USE1");
		signup("THREADS-USE1", "signup-code-use1@example.com").andExpect(status().isCreated());

		Map<String, Object> row = jdbcClient.sql(
				"SELECT used_by, used_at FROM app.signup_codes WHERE code = 'THREADS-USE1'")
				.query().singleRow();
		Long userId = jdbcClient.sql("SELECT id FROM app.users WHERE email = 'signup-code-use1@example.com'")
				.query(Long.class).single();
		assertThat(row.get("used_by")).isEqualTo(userId);
		assertThat(row.get("used_at")).isNotNull();

		// 소진된 코드는 사전 검증도 403
		verify("THREADS-USE1").andExpect(status().isForbidden());
	}

	@Test
	void 소진된_코드로_재가입은_403이고_계정이_생기지_않는다() throws Exception {
		seedCode("THREADS-USE2");
		signup("THREADS-USE2", "signup-code-first@example.com").andExpect(status().isCreated());

		signup("THREADS-USE2", "signup-code-second@example.com")
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error.code").value("INVALID_SIGNUP_CODE"));

		Long count = jdbcClient.sql("SELECT count(*) FROM app.users WHERE email = 'signup-code-second@example.com'")
				.query(Long.class).single();
		assertThat(count).isZero();
	}

	@Test
	void 중복_이메일로_가입이_실패하면_코드는_소진되지_않는다() throws Exception {
		seedCode("THREADS-DUP1");
		signup("THREADS-DUP1", "signup-code-dup@example.com").andExpect(status().isCreated());

		seedCode("THREADS-DUP2");
		signup("THREADS-DUP2", "signup-code-dup@example.com")
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("EMAIL_ALREADY_EXISTS"));

		Object usedBy = jdbcClient.sql("SELECT used_by FROM app.signup_codes WHERE code = 'THREADS-DUP2'")
				.query().singleRow().get("used_by");
		assertThat(usedBy).isNull();
	}

	@Test
	void 코드_테이블에_없는_코드로_가입은_403이다() throws Exception {
		signup("GHOST-CODE", "signup-code-ghost@example.com")
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error.code").value("INVALID_SIGNUP_CODE"));
	}

	@Test
	void super_코드는_여러_명이_가입할_수_있고_used_at이_찍히지_않는다() throws Exception {
		seedSuperCode("SUPER-MULTI");
		signup("SUPER-MULTI", "super-multi-1@example.com").andExpect(status().isCreated());
		signup("SUPER-MULTI", "super-multi-2@example.com").andExpect(status().isCreated());

		Map<String, Object> row = jdbcClient.sql(
				"SELECT used_by, used_at FROM app.signup_codes WHERE code = 'SUPER-MULTI'")
				.query().singleRow();
		assertThat(row.get("used_by")).isNull();
		assertThat(row.get("used_at")).isNull();

		// 두 명 가입 후에도 사전 검증은 계속 valid
		verify("SUPER-MULTI")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.valid").value(true));
	}

	@Test
	void 소진된_일반_코드를_super로_승격하면_다시_가입할_수_있다() throws Exception {
		seedCode("THREADS-PROMO");
		signup("THREADS-PROMO", "promo-first@example.com").andExpect(status().isCreated());
		// 소진 확인 후 승격 — 기존 used_at 스탬프는 보존된 채 무제한이 된다(설계 §동작 규칙).
		jdbcClient.sql("UPDATE app.signup_codes SET is_super = true WHERE code = 'THREADS-PROMO'").update();

		signup("THREADS-PROMO", "promo-second@example.com").andExpect(status().isCreated());
		verify("THREADS-PROMO").andExpect(status().isOk());
	}

	@Test
	void super를_강등하면_일반_1회용_규칙으로_복귀한다() throws Exception {
		seedSuperCode("SUPER-DEMOTE");
		signup("SUPER-DEMOTE", "demote-first@example.com").andExpect(status().isCreated());
		// 강등 — super 가입은 used_at을 안 찍었으므로 미소진 일반 코드가 된다.
		jdbcClient.sql("UPDATE app.signup_codes SET is_super = false WHERE code = 'SUPER-DEMOTE'").update();

		signup("SUPER-DEMOTE", "demote-second@example.com").andExpect(status().isCreated());
		// 두 번째 가입이 스탬프를 찍었으니 이제 소진 — 세 번째는 403.
		signup("SUPER-DEMOTE", "demote-third@example.com")
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error.code").value("INVALID_SIGNUP_CODE"));
	}

	@Test
	void 일반_코드_소진은_super_도입_후에도_그대로다() throws Exception {
		seedCode("THREADS-STILL1");
		signup("THREADS-STILL1", "still-first@example.com").andExpect(status().isCreated());
		signup("THREADS-STILL1", "still-second@example.com")
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error.code").value("INVALID_SIGNUP_CODE"));
	}
}
