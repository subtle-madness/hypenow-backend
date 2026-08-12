package com.celfit.was;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.celfit.was.mail.MailSendException;
import com.celfit.was.mail.MailSender;
import jakarta.servlet.http.Cookie;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 비밀번호 재설정 3단계(send→confirm→reset) 통합 테스트 — RecordingMailSender로 발송을
 * 가로채 코드를 캡처한다. 레이트리밋이 전역 싱글턴이라 테스트마다 고유 이메일을 쓰고,
 * IP 한도도 간섭하지 않도록 테스트 메서드마다 고유 remoteAddr을 부여한다.
 */
@AutoConfigureMockMvc
class PasswordResetIntegrationTest extends IntegrationTest {

	@TestConfiguration
	static class MailTestConfig {

		@Bean
		@Primary
		RecordingMailSender recordingMailSender() {
			return new RecordingMailSender();
		}
	}

	static class RecordingMailSender implements MailSender {

		final List<String> texts = new ArrayList<>();
		boolean failNext;

		@Override
		public void send(String to, String subject, String text) {
			if (failNext) {
				failNext = false;
				throw new MailSendException("주입된 실패");
			}
			texts.add(text);
		}

		String lastCode() {
			Matcher m = Pattern.compile("\\d{6}").matcher(texts.get(texts.size() - 1));
			if (!m.find()) {
				throw new IllegalStateException("발송 본문에 6자리 코드 없음: " + texts);
			}
			return m.group();
		}
	}

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcClient jdbcClient;

	@Autowired
	RecordingMailSender mail;

	private static final AtomicInteger SEQUENCE = new AtomicInteger();

	private String email;
	private String testIp;

	@BeforeEach
	void setUp() {
		int seq = SEQUENCE.incrementAndGet();
		email = "pw-reset-" + seq + "@example.com";
		testIp = "10.9." + (seq / 250) + "." + (seq % 250 + 1);
		V1AuthTestSteps.enableSignupCode(jdbcClient);
	}

	// --- 헬퍼 ---

	/** 가입 헬퍼 — 반환 쿠키는 가입 시 자동 로그인된 세션(reset의 전 세션 무효화 검증에 사용). */
	private Cookie signUp() throws Exception {
		return V1AuthTestSteps.signUp(mockMvc, jdbcClient, email);
	}

	private org.springframework.test.web.servlet.ResultActions send() throws Exception {
		return mockMvc.perform(post("/v1/auth/password-reset/send").with(csrf())
				.with(req -> { req.setRemoteAddr(testIp); return req; })
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\": \"" + email + "\"}"));
	}

	private org.springframework.test.web.servlet.ResultActions confirm(String code) throws Exception {
		return mockMvc.perform(post("/v1/auth/password-reset/confirm").with(csrf())
				.with(req -> { req.setRemoteAddr(testIp); return req; })
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\": \"" + email + "\", \"code\": \"" + code + "\"}"));
	}

	private org.springframework.test.web.servlet.ResultActions reset(String token, String newPassword) throws Exception {
		return mockMvc.perform(post("/v1/auth/password-reset").with(csrf())
				.with(req -> { req.setRemoteAddr(testIp); return req; })
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"resetToken\": \"" + token + "\", \"newPassword\": \"" + newPassword + "\"}"));
	}

	/** send→confirm까지 완주하고 resetToken을 돌려준다. */
	private String issueToken() throws Exception {
		send().andExpect(status().isNoContent());
		String body = confirm(mail.lastCode()).andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		Matcher m = Pattern.compile("\"resetToken\"\\s*:\\s*\"([^\"]+)\"").matcher(body);
		assertThat(m.find()).isTrue();
		return m.group(1);
	}

	// --- send ---

	@Test
	void 가입_안_된_이메일_발송은_404_USER_NOT_FOUND() throws Exception {
		send().andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("USER_NOT_FOUND"));
	}

	@Test
	void 가입_이메일_발송은_204_코드_행_생성() throws Exception {
		signUp();
		send().andExpect(status().isNoContent());

		assertThat(mail.lastCode()).hasSize(6);
		Integer rows = jdbcClient.sql("SELECT count(*) FROM app.password_resets WHERE email = :email")
				.param("email", email).query(Integer.class).single();
		assertThat(rows).isEqualTo(1);
	}

	@Test
	void 재발송_쿨다운_60초는_429() throws Exception {
		signUp();
		send().andExpect(status().isNoContent());
		// 분 경계에 걸치면 2번째가 통과할 수 있다 — 그 경우 같은 분의 3번째로 판정(경계 2회 연속은 불가능)
		var second = send().andReturn();
		if (second.getResponse().getStatus() != 429) {
			send().andExpect(status().isTooManyRequests())
					.andExpect(jsonPath("$.error.code").value("RATE_LIMITED"));
		}
	}

	@Test
	void 발송_실패는_502_EMAIL_SEND_FAILED_행_미생성() throws Exception {
		signUp();
		mail.failNext = true;
		send().andExpect(status().isBadGateway())
				.andExpect(jsonPath("$.error.code").value("EMAIL_SEND_FAILED"));

		Integer rows = jdbcClient.sql("SELECT count(*) FROM app.password_resets WHERE email = :email")
				.param("email", email).query(Integer.class).single();
		assertThat(rows).isZero();
	}

	// --- confirm ---

	@Test
	void 코드_일치_confirm은_토큰을_발급하고_코드를_소모한다() throws Exception {
		signUp();
		send().andExpect(status().isNoContent());
		String code = mail.lastCode();

		confirm(code).andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data.resetToken").value(org.hamcrest.Matchers.startsWith("prt_")))
				.andExpect(jsonPath("$.data.expiresIn").value(600));

		// 같은 코드 재confirm은 소모돼 실패
		confirm(code).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("INVALID_VERIFICATION_CODE"));
	}

	@Test
	void 오입력_5회_누적_후에는_정답도_거부된다() throws Exception {
		signUp();
		send().andExpect(status().isNoContent());
		String code = mail.lastCode();

		for (int i = 0; i < 5; i++) {
			confirm("000000".equals(code) ? "111111" : "000000")
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.error.code").value("INVALID_VERIFICATION_CODE"));
		}
		confirm(code).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("INVALID_VERIFICATION_CODE"));
	}

	@Test
	void 만료된_코드는_거부된다() throws Exception {
		signUp();
		send().andExpect(status().isNoContent());
		jdbcClient.sql("UPDATE app.password_resets SET code_expires_at = now() - interval '1 second' WHERE email = :email")
				.param("email", email).update();

		confirm(mail.lastCode()).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("INVALID_VERIFICATION_CODE"));
	}

	// --- reset ---

	@Test
	void reset_성공은_204_비밀번호_교체_세션_전부_무효화_자동로그인_없음() throws Exception {
		Cookie oldSession = signUp();
		String token = issueToken();

		// 자동 로그인 없음 — 세션 쿠키 미발급(hypenow-session, SessionConfig 참조)
		reset(token, "newPassw0rd").andExpect(status().isNoContent())
				.andExpect(cookie().doesNotExist("hypenow-session"));

		// 기존 세션 무효화 — 이전 세션 쿠키로 /v1/me가 더 이상 통하지 않는다
		mockMvc.perform(get("/v1/me").cookie(oldSession)).andExpect(status().isUnauthorized());
		// 새 비밀번호로 로그인 성공, 옛 비밀번호는 실패
		login("newPassw0rd").andExpect(status().isOk());
		login(V1AuthTestSteps.PASSWORD).andExpect(status().isUnauthorized());
		// 행 삭제(토큰 소비) 확인
		Integer rows = jdbcClient.sql("SELECT count(*) FROM app.password_resets WHERE email = :email")
				.param("email", email).query(Integer.class).single();
		assertThat(rows).isZero();
	}

	@Test
	void 토큰_재사용은_400_INVALID_RESET_TOKEN() throws Exception {
		signUp();
		String token = issueToken();
		reset(token, "newPassw0rd").andExpect(status().isNoContent());

		reset(token, "anotherPass1").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("INVALID_RESET_TOKEN"));
	}

	@Test
	void 만료된_토큰은_400_INVALID_RESET_TOKEN() throws Exception {
		signUp();
		String token = issueToken();
		jdbcClient.sql("UPDATE app.password_resets SET token_expires_at = now() - interval '1 second' WHERE email = :email")
				.param("email", email).update();

		reset(token, "newPassw0rd").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("INVALID_RESET_TOKEN"));
	}

	@Test
	void 위조_토큰은_400_INVALID_RESET_TOKEN() throws Exception {
		reset("prt_0000000000000000000000000000000000000000000000000000000000000000", "newPassw0rd")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("INVALID_RESET_TOKEN"));
	}

	@Test
	void 빈_비밀번호는_400_VALIDATION_FAILED_토큰은_생존한다() throws Exception {
		signUp();
		String token = issueToken();

		reset(token, "").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
		// 토큰이 소모되지 않아 재시도 성공
		reset(token, "newPassw0rd").andExpect(status().isNoContent());
	}

	private org.springframework.test.web.servlet.ResultActions login(String password) throws Exception {
		return mockMvc.perform(post("/v1/auth/login").with(csrf())
				.with(req -> { req.setRemoteAddr(testIp); return req; })
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\": \"" + email + "\", \"password\": \"" + password + "\"}"));
	}
}
