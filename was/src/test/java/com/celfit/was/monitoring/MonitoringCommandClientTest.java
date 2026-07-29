package com.celfit.was.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class MonitoringCommandClientTest {

	static final String BASE = "http://monitoring:8083";

	MockRestServiceServer server;
	MonitoringCommandClient client;

	@BeforeEach
	void setUp() {
		RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
		server = MockRestServiceServer.bindTo(builder).build();
		client = new MonitoringCommandClient(builder.build());
	}

	@Test
	void 계정_등록_요청과_응답_파싱() {
		UUID key = UUID.randomUUID();
		server.expect(requestTo(BASE + "/api/targets"))
				.andExpect(method(HttpMethod.POST))
				.andExpect(jsonPath("$.registrationKey").value(key.toString()))
				.andExpect(jsonPath("$.type").value("ACCOUNT"))
				.andExpect(jsonPath("$.keywordRule.and[0]").value("샤넬"))
				.andExpect(jsonPath("$.shortCode").doesNotExist())   // NON_NULL — POST 전용 필드 미직렬화
				.andRespond(withSuccess("""
						{ "targetId": 17, "status": "WATCHING",
						  "firstSnapshot": { "profile": { "followers": 12345 }, "recentPostCount": 12 } }
						""", MediaType.APPLICATION_JSON));

		RegisterResult result = client.register(RegisterRequest.account(key, "some_influencer",
				new KeywordRule(List.of("샤넬"), List.of(), List.of("이벤트")),
				OffsetDateTime.parse("2026-08-28T23:59:59+09:00")));

		assertThat(result.targetId()).isEqualTo(17L);
		assertThat(result.status()).isEqualTo("WATCHING");
		assertThat(result.firstSnapshot().path("profile").path("followers").asLong()).isEqualTo(12345L);
		server.verify();
	}

	@Test
	void 에러_바디의_code가_그대로_승격된다() {
		server.expect(requestTo(BASE + "/api/targets"))
				.andRespond(withStatus(HttpStatus.NOT_FOUND)
						.contentType(MediaType.APPLICATION_JSON)
						.body("{ \"code\": \"SUBJECT_NOT_FOUND\", \"message\": \"계정을 찾을 수 없음: @foo\" }"));

		assertThatThrownBy(() -> client.register(RegisterRequest.post(UUID.randomUUID(), "DAbC",
				OffsetDateTime.parse("2026-08-28T23:59:59+09:00"))))
				.isInstanceOfSatisfying(MonitoringApiException.class, e -> {
					assertThat(e.code()).isEqualTo("SUBJECT_NOT_FOUND");
					assertThat(e.httpStatus()).isEqualTo(404);
				});
	}

	@Test
	void 바디_없는_5xx는_Unavailable() {
		server.expect(requestTo(BASE + "/api/targets"))
				.andRespond(withStatus(HttpStatus.BAD_GATEWAY));

		assertThatThrownBy(() -> client.register(RegisterRequest.post(UUID.randomUUID(), "DAbC",
				OffsetDateTime.parse("2026-08-28T23:59:59+09:00"))))
				.isInstanceOf(MonitoringUnavailableException.class);
	}

	@Test
	void 승인_기각_연장_해지_경로() {
		server.expect(requestTo(BASE + "/api/targets/17/candidates/3/approve"))
				.andExpect(method(HttpMethod.POST))
				.andRespond(withSuccess(
						"{ \"targetId\": 17, \"status\": \"TRACKING\", \"trackedShortCode\": \"DAbC\" }",
						MediaType.APPLICATION_JSON));
		server.expect(requestTo(BASE + "/api/targets/17/candidates/4/reject"))
				.andExpect(method(HttpMethod.POST))
				.andRespond(withSuccess("{ \"candidateId\": 4, \"status\": \"REJECTED\" }",
						MediaType.APPLICATION_JSON));
		server.expect(requestTo(BASE + "/api/targets/17"))
				.andExpect(method(HttpMethod.PATCH))
				.andExpect(jsonPath("$.expiresAt").exists())
				.andRespond(withSuccess(
						"{ \"targetId\": 17, \"expiresAt\": \"2026-09-30T23:59:59+09:00\" }",
						MediaType.APPLICATION_JSON));
		server.expect(requestTo(BASE + "/api/targets/17"))
				.andExpect(method(HttpMethod.DELETE))
				.andRespond(withSuccess("{ \"targetId\": 17, \"status\": \"CANCELED\" }",
						MediaType.APPLICATION_JSON));

		assertThat(client.approve(17, 3).trackedShortCode()).isEqualTo("DAbC");
		assertThat(client.reject(17, 4).status()).isEqualTo("REJECTED");
		assertThat(client.extend(17, OffsetDateTime.parse("2026-09-30T23:59:59+09:00")).targetId()).isEqualTo(17L);
		assertThat(client.cancel(17).status()).isEqualTo("CANCELED");
		server.verify();
	}
}
