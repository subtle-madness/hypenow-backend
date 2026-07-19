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
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

/**
 * 도입문의 계약(클로즈베타 설계 2026-07-19) — POST /v1/inquiries 실 DB 검증.
 * 코드 없는 방문자가 남기는 공개 표면이라 익명 접근·저장·검증을 함께 커버한다.
 * 레이트리밋이 전역 싱글턴이라 테스트 메서드마다 고유 remoteAddr을 부여한다(이메일 인증 테스트 관용구).
 */
@AutoConfigureMockMvc
class InquiryIntegrationTest extends IntegrationTest {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcClient jdbcClient;

	private static final AtomicInteger IP_SEQUENCE = new AtomicInteger();

	private String testIp;

	@BeforeEach
	void setUp() {
		int seq = IP_SEQUENCE.incrementAndGet();
		testIp = "10.2.%d.%d".formatted(seq / 256, seq % 256);
	}

	private org.springframework.test.web.servlet.ResultActions submit(String body) throws Exception {
		return mockMvc.perform(post("/v1/inquiries").with(csrf())
				.with(request -> {
					request.setRemoteAddr(testIp);
					return request;
				})
				.contentType(MediaType.APPLICATION_JSON)
				.content(body));
	}

	private static final String VALID_BODY = """
			{"userType":"brand","name":"홍길동","email":"inquiry@example.com",
			 "organization":"OO코스메틱","message":"클로즈베타 초대코드를 받고 싶습니다."}""";

	@Test
	void 익명_문의는_201과_id를_반환하고_저장된다() throws Exception {
		MvcResult result = submit(VALID_BODY)
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data.id").isNotEmpty())
				.andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.nullValue()))
				.andReturn();

		String id = new ObjectMapper().readTree(result.getResponse().getContentAsString())
				.get("data").get("id").asString();
		Map<String, Object> row = jdbcClient.sql(
				"SELECT user_type, name, email, organization, message FROM app.inquiries WHERE id = :id::uuid")
				.param("id", id)
				.query().singleRow();
		assertThat(row.get("user_type")).isEqualTo("brand");
		assertThat(row.get("name")).isEqualTo("홍길동");
		assertThat(row.get("email")).isEqualTo("inquiry@example.com");
		assertThat(row.get("organization")).isEqualTo("OO코스메틱");
		assertThat(row.get("message")).isEqualTo("클로즈베타 초대코드를 받고 싶습니다.");
	}

	@Test
	void 필수_필드_누락은_400_VALIDATION_FAILED다() throws Exception {
		submit("""
				{"userType":"brand","name":"","email":"inquiry@example.com",
				 "organization":"OO코스메틱","message":"문의"}""")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
	}

	@Test
	void userType_허용값_밖은_400이다() throws Exception {
		submit("""
				{"userType":"hacker","name":"홍길동","email":"inquiry@example.com",
				 "organization":"OO코스메틱","message":"문의"}""")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
	}

	@Test
	void 이메일_형식_위반은_400이다() throws Exception {
		submit("""
				{"userType":"brand","name":"홍길동","email":"not-an-email",
				 "organization":"OO코스메틱","message":"문의"}""")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
	}

	@Test
	void 분당_2회_초과는_429_RATE_LIMITED다() throws Exception {
		submit(VALID_BODY).andExpect(status().isCreated());
		submit(VALID_BODY).andExpect(status().isCreated());
		submit(VALID_BODY)
				.andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.error.code").value("RATE_LIMITED"));
	}
}
