package com.celfit.was;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.ObjectMapper;

/**
 * 가입 시도 추적(app.signup_events, 2026-07-29) 통합 테스트 — 요청 1건당 1행이 실 DB에 남고,
 * detail의 progress가 내부 어느 단계까지 갔는지 담는지를 검증한다. jsonb는 키 순서·공백을
 * 정규화하므로 detail은 텍스트 매칭 대신 파싱해 구조로 단언한다. 이메일은 테스트마다 고유
 * (이벤트 테이블은 격리 삭제 없이 이메일로 구분), 가입 레이트리밋(IP당 분당 10회)이 싱글턴이라
 * 다른 스위트와 합산되지 않게 테스트 메서드마다 고유 remoteAddr을 부여한다.
 */
@AutoConfigureMockMvc
class SignupEventIntegrationTest extends IntegrationTest {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcClient jdbcClient;

	@Autowired
	ObjectMapper objectMapper;

	private static final AtomicInteger IP_SEQUENCE = new AtomicInteger();

	private String testIp;

	@BeforeEach
	void setUp() {
		int seq = IP_SEQUENCE.incrementAndGet();
		testIp = "10.9.%d.%d".formatted(seq / 256, seq % 256);
	}

	private ResultActions postSignup(String body) throws Exception {
		return mockMvc.perform(post("/v1/auth/signup").with(csrf())
				.with(req -> {
					req.setRemoteAddr(testIp);
					return req;
				})
				.contentType(MediaType.APPLICATION_JSON)
				.content(body));
	}

	private record Event(String outcome, String detailJson) {
	}

	private Event singleEvent(String email) {
		return jdbcClient.sql(
						"SELECT outcome, detail::text AS detail FROM app.signup_events WHERE email = :email")
				.param("email", email)
				.query((rs, i) -> new Event(rs.getString("outcome"), rs.getString("detail")))
				.single();
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> detail(Event event) {
		return objectMapper.readValue(event.detailJson(), Map.class);
	}

	@Test
	void 가입_성공은_ok_이벤트와_전체_progress를_남긴다() throws Exception {
		V1AuthTestSteps.enableSignupCode(jdbcClient);
		postSignup(V1AuthTestSteps.signupBody("event-ok@example.com"))
				.andExpect(status().isCreated());

		Event event = singleEvent("event-ok@example.com");
		assertThat(event.outcome()).isEqualTo("ok");
		Map<String, Object> detail = detail(event);
		assertThat(detail.get("progress")).isEqualTo(
				List.of("rate_limit", "signup_code", "validation", "register", "auto_login", "session"));
		assertThat(detail.get("signupCode")).isEqualTo(V1AuthTestSteps.SIGNUP_CODE);
		assertThat(detail).containsKey("userId");
		assertThat(event.detailJson()).doesNotContain(V1AuthTestSteps.PASSWORD); // 비밀번호 불포함 계약
	}

	@Test
	void 가입_코드_무효는_INVALID_SIGNUP_CODE_이벤트를_남기고_progress는_rate_limit뿐이다() throws Exception {
		postSignup(V1AuthTestSteps.signupBody("event-badcode@example.com")
				.replace(V1AuthTestSteps.SIGNUP_CODE, "NO-SUCH-CODE"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error.code").value("INVALID_SIGNUP_CODE"));

		Event event = singleEvent("event-badcode@example.com");
		assertThat(event.outcome()).isEqualTo("INVALID_SIGNUP_CODE");
		Map<String, Object> detail = detail(event);
		assertThat(detail.get("progress")).isEqualTo(List.of("rate_limit"));
		assertThat(detail).containsKey("message");
		assertThat(detail.get("signupCode")).isEqualTo("NO-SUCH-CODE"); // 실패한 코드값도 남는다
	}

	@Test
	void 검증_실패는_VALIDATION_FAILED_이벤트를_남기고_실패_사유_메시지를_담는다() throws Exception {
		V1AuthTestSteps.enableSignupCode(jdbcClient);
		postSignup(V1AuthTestSteps.signupBody("event-badreq@example.com")
				.replace("\"name\":\"김우민\"", "\"name\":\" \""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

		Event event = singleEvent("event-badreq@example.com");
		assertThat(event.outcome()).isEqualTo("VALIDATION_FAILED");
		Map<String, Object> detail = detail(event);
		assertThat(detail.get("progress")).isEqualTo(List.of("rate_limit", "signup_code"));
		assertThat((String) detail.get("message")).contains("이름을 입력해 주세요");
	}

	@Test
	void 중복_이메일은_EMAIL_ALREADY_EXISTS_이벤트를_남기고_validation까지_통과가_보인다() throws Exception {
		V1AuthTestSteps.enableSignupCode(jdbcClient);
		postSignup(V1AuthTestSteps.signupBody("event-dup@example.com"))
				.andExpect(status().isCreated());
		V1AuthTestSteps.enableSignupCode(jdbcClient);

		postSignup(V1AuthTestSteps.signupBody("event-dup@example.com"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("EMAIL_ALREADY_EXISTS"));

		Event failed = jdbcClient.sql("""
						SELECT outcome, detail::text AS detail FROM app.signup_events
						WHERE email = 'event-dup@example.com' AND outcome = 'EMAIL_ALREADY_EXISTS'""")
				.query((rs, i) -> new Event(rs.getString("outcome"), rs.getString("detail")))
				.single();
		assertThat(detail(failed).get("progress"))
				.isEqualTo(List.of("rate_limit", "signup_code", "validation"));
	}
}
