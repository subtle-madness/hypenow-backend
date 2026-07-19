package com.celfit.was;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.celfit.was.mail.MailSendException;
import com.celfit.was.mail.MailSender;
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
 * 이메일 소유권 인증(설계 2026-07-18) 통합 테스트 — RecordingMailSender로 발송 내용을 가로채
 * 코드를 캡처한다. 레이트리밋이 전역 싱글턴이라 테스트마다 고유 이메일을 쓰고, IP 한도(발송 분당
 * 5회)도 클래스 전체가 공유하지 않도록 테스트 메서드마다 고유 remoteAddr을 부여한다.
 */
@AutoConfigureMockMvc
class EmailVerificationIntegrationTest extends IntegrationTest {

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

	/** IP 레이트리밋(발송 분당 5회)이 싱글턴이라 테스트 메서드끼리 간섭하지 않게 메서드마다 고유 IP. */
	private static final AtomicInteger IP_SEQUENCE = new AtomicInteger();

	private String testIp;

	@BeforeEach
	void setUp() {
		V1AuthTestSteps.enableSignupCode(jdbcClient);
		jdbcClient.sql("DELETE FROM app.email_verifications").update();
		mail.texts.clear();
		mail.failNext = false;
		int seq = IP_SEQUENCE.incrementAndGet();
		testIp = "10.0.%d.%d".formatted(seq / 256, seq % 256);
	}

	private void send(String email, int expectedStatus) throws Exception {
		mockMvc.perform(post("/v1/auth/email-verification/send").with(csrf())
						.with(req -> {
							req.setRemoteAddr(testIp);
							return req;
						})
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"email\":\"%s\"}".formatted(email)))
				.andExpect(status().is(expectedStatus));
	}

	private void confirm(String email, String code, int expectedStatus) throws Exception {
		mockMvc.perform(post("/v1/auth/email-verification/confirm").with(csrf())
						.with(req -> {
							req.setRemoteAddr(testIp);
							return req;
						})
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"email\":\"%s\",\"code\":\"%s\"}".formatted(email, code)))
				.andExpect(status().is(expectedStatus));
	}

	@Test
	void 발송_확인_해피패스_verified_마킹() throws Exception {
		send("verify-happy@example.com", 204);
		confirm("verify-happy@example.com", mail.lastCode(), 204);

		Boolean verified = jdbcClient.sql(
						"SELECT verified_at IS NOT NULL FROM app.email_verifications WHERE email = 'verify-happy@example.com'")
				.query(Boolean.class).single();
		assertThat(verified).isTrue();
	}

	@Test
	void 형식_오류는_400() throws Exception {
		send("not-an-email", 400);
	}

	@Test
	void 같은_이메일_분당_2회_발송은_429() throws Exception {
		send("verify-cooldown@example.com", 204);
		send("verify-cooldown@example.com", 429);
	}

	@Test
	void 오입력_5회_초과시_정답도_거부() throws Exception {
		send("verify-attempts@example.com", 204);
		String code = mail.lastCode();
		String wrong = code.equals("000000") ? "000001" : "000000";
		for (int i = 0; i < 5; i++) {
			confirm("verify-attempts@example.com", wrong, 400);
		}
		confirm("verify-attempts@example.com", code, 400);
	}

	@Test
	void 만료된_코드는_400() throws Exception {
		send("verify-expired@example.com", 204);
		jdbcClient.sql("""
				UPDATE app.email_verifications SET code_expires_at = now() - interval '1 minute'
				WHERE email = 'verify-expired@example.com'""").update();
		confirm("verify-expired@example.com", mail.lastCode(), 400);
	}

	@Test
	void 발송_이력_없는_이메일_confirm은_400() throws Exception {
		confirm("verify-none@example.com", "123456", 400);
	}

	@Test
	void 기가입_이메일_발송은_409() throws Exception {
		V1AuthTestSteps.signUp(mockMvc, jdbcClient, "verify-dup@example.com");
		send("verify-dup@example.com", 409);
	}

	@Test
	void 발송_실패는_502_이고_행을_남기지_않는다() throws Exception {
		mail.failNext = true;
		mockMvc.perform(post("/v1/auth/email-verification/send").with(csrf())
						.with(req -> {
							req.setRemoteAddr(testIp);
							return req;
						})
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"email\":\"verify-fail@example.com\"}"))
				.andExpect(status().isBadGateway())
				.andExpect(jsonPath("$.error.code").value("EMAIL_SEND_FAILED"));
		Long count = jdbcClient.sql(
						"SELECT count(*) FROM app.email_verifications WHERE email = 'verify-fail@example.com'")
				.query(Long.class).single();
		assertThat(count).isZero();
	}

	@Test
	void 재발송하면_이전_코드는_무효() throws Exception {
		// 컨트롤러 쿨다운(분당 1회)을 우회해 서비스 계약만 검증 — 재발송 시 code_hash가 교체됨을 DB 조작으로 재현
		send("verify-resend@example.com", 204);
		String first = mail.lastCode();
		jdbcClient.sql("""
				UPDATE app.email_verifications SET code_hash = 'replaced-by-resend'
				WHERE email = 'verify-resend@example.com'""").update();
		confirm("verify-resend@example.com", first, 400);
	}

	@Test
	void 미인증_이메일_가입은_403_EMAIL_NOT_VERIFIED() throws Exception {
		mockMvc.perform(post("/v1/auth/signup").with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content(V1AuthTestSteps.signupBody("verify-gate@example.com")))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error.code").value("EMAIL_NOT_VERIFIED"));
	}

	@Test
	void 인증_30분_초과_가입은_403() throws Exception {
		V1AuthTestSteps.markEmailVerified(jdbcClient, "verify-stale@example.com");
		jdbcClient.sql("""
				UPDATE app.email_verifications SET verified_at = now() - interval '31 minutes'
				WHERE email = 'verify-stale@example.com'""").update();
		mockMvc.perform(post("/v1/auth/signup").with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content(V1AuthTestSteps.signupBody("verify-stale@example.com")))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error.code").value("EMAIL_NOT_VERIFIED"));
	}

	@Test
	void 가입_성공시_인증_행이_소비된다() throws Exception {
		V1AuthTestSteps.signUp(mockMvc, jdbcClient, "verify-consume@example.com");
		Long count = jdbcClient.sql(
						"SELECT count(*) FROM app.email_verifications WHERE email = 'verify-consume@example.com'")
				.query(Long.class).single();
		assertThat(count).isZero();
	}

	@Test
	void 가입_코드_검증이_이메일_인증보다_먼저다() throws Exception {
		// 미인증 + 잘못된 가입 코드 → INVALID_SIGNUP_CODE(스펙 §4 순서: 가입 코드가 선행)
		String body = V1AuthTestSteps.signupBody("verify-order@example.com")
				.replace(V1AuthTestSteps.SIGNUP_CODE, "WRONG-CODE");
		mockMvc.perform(post("/v1/auth/signup").with(csrf())
						.contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error.code").value("INVALID_SIGNUP_CODE"));
	}
}
