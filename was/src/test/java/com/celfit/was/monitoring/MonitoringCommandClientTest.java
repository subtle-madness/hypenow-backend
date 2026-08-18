package com.celfit.was.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
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
				.andExpect(jsonPath("$.userId").value(12345))
				.andExpect(jsonPath("$.type").value("ACCOUNT"))
				.andExpect(jsonPath("$.keywordRule.and[0]").value("샤넬"))
				.andExpect(jsonPath("$.shortCode").doesNotExist())   // NON_NULL — POST 전용 필드 미직렬화
				.andRespond(withSuccess("""
						{ "targetId": 17, "status": "WATCHING",
						  "firstSnapshot": { "profile": { "followers": 12345 }, "recentPostCount": 12 } }
						""", MediaType.APPLICATION_JSON));

		RegisterResult result = client.register(RegisterRequest.account(key, 12345L, "some_influencer",
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

		assertThatThrownBy(() -> client.register(RegisterRequest.post(UUID.randomUUID(), 12345L, "DAbC",
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

		assertThatThrownBy(() -> client.register(RegisterRequest.post(UUID.randomUUID(), 12345L, "DAbC",
				OffsetDateTime.parse("2026-08-28T23:59:59+09:00"))))
				.isInstanceOf(MonitoringUnavailableException.class);
	}

	@Test
	void 접속_실패는_Unavailable() {
		server.expect(requestTo(BASE + "/api/targets"))
				.andRespond(withException(new java.net.SocketTimeoutException("read timeout")));

		assertThatThrownBy(() -> client.register(RegisterRequest.post(UUID.randomUUID(), 12345L, "DAbC",
				OffsetDateTime.parse("2026-08-28T23:59:59+09:00"))))
				.isInstanceOf(MonitoringUnavailableException.class);
	}

	@Test
	void 성공_응답인데_바디_해석_불가면_Unavailable() {
		server.expect(requestTo(BASE + "/api/targets"))
				.andRespond(withSuccess("{ 깨진 json", MediaType.APPLICATION_JSON));

		assertThatThrownBy(() -> client.register(RegisterRequest.post(UUID.randomUUID(), 12345L, "DAbC",
				OffsetDateTime.parse("2026-08-28T23:59:59+09:00"))))
				.isInstanceOf(MonitoringUnavailableException.class);
	}

	@Test
	void 연장_해지_경로() {
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

		assertThat(client.extend(17, OffsetDateTime.parse("2026-09-30T23:59:59+09:00")).targetId()).isEqualTo(17L);
		assertThat(client.cancel(17).status()).isEqualTo("CANCELED");
		server.verify();
	}

	@Test
	void share_해소_요청과_응답_파싱() {
		server.expect(requestTo(BASE + "/api/share/resolve"))
				.andExpect(method(HttpMethod.POST))
				.andExpect(jsonPath("$.url").value("https://www.instagram.com/share/reel/AbCdEfG/"))
				// 콜 집계 귀속용 userId(2026-08-12 비용 범위 확장) — 본문에 함께 실린다.
				.andExpect(jsonPath("$.userId").value(7))
				.andRespond(withSuccess("""
						{ "shortCode": "DbV7LgZsKG8", "username": "rarebeauty", "contentType": "REELS" }
						""", MediaType.APPLICATION_JSON));

		ShareResolveResult result = client.resolveShare("https://www.instagram.com/share/reel/AbCdEfG/", 7L);

		assertThat(result.shortCode()).isEqualTo("DbV7LgZsKG8");
		assertThat(result.username()).isEqualTo("rarebeauty");
		assertThat(result.contentType()).isEqualTo("REELS");
		server.verify();
	}

	@Test
	void share_해소_실패_코드가_그대로_승격된다() {
		server.expect(requestTo(BASE + "/api/share/resolve"))
				.andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY)
						.contentType(MediaType.APPLICATION_JSON)
						.body("{ \"code\": \"SHARE_LINK_UNRESOLVED\", \"message\": \"링크를 해소할 수 없음\" }"));

		assertThatThrownBy(() -> client.resolveShare("https://www.instagram.com/share/reel/bad/", 7L))
				.isInstanceOfSatisfying(MonitoringApiException.class,
						e -> assertThat(e.code()).isEqualTo("SHARE_LINK_UNRESOLVED"));
	}

	// ---------- direct 게시물 명령(2026-08-18 direct 통합 §T7) ----------

	@Test
	void direct_등록_201_신규_수집_응답_파싱() {
		server.expect(requestTo(BASE + "/api/brands/100/direct-posts"))
				.andExpect(method(HttpMethod.POST))
				.andExpect(jsonPath("$.shortCode").value("ABC123"))
				.andExpect(jsonPath("$.registeredAt").doesNotExist())
				.andExpect(jsonPath("$.importLegacyHistory").value(false))
				.andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON)
						.body("""
								{ "shortCode": "ABC123", "authorUsername": "creator", "takenAt": "2026-08-01T00:00:00Z",
								  "contentType": "REELS" }
								"""));

		MonitoringCommandClient.DirectPostResult result = client.registerDirectPost(100L, "ABC123", null, false);

		assertThat(result.shortCode()).isEqualTo("ABC123");
		assertThat(result.authorUsername()).isEqualTo("creator");
		assertThat(result.contentType()).isEqualTo("REELS");
		server.verify();
	}

	@Test
	void direct_등록_200_멱등_응답도_같은_셰이프로_파싱된다() {
		server.expect(requestTo(BASE + "/api/brands/100/direct-posts"))
				.andRespond(withSuccess("""
						{ "shortCode": "ABC123", "authorUsername": "creator", "takenAt": "2026-08-01T00:00:00Z",
						  "contentType": "REELS" }
						""", MediaType.APPLICATION_JSON));

		MonitoringCommandClient.DirectPostResult result = client.registerDirectPost(100L, "ABC123", null, false);

		assertThat(result.shortCode()).isEqualTo("ABC123");
	}

	@Test
	void direct_등록_404_POST_NOT_FOUND가_그대로_승격된다() {
		server.expect(requestTo(BASE + "/api/brands/100/direct-posts"))
				.andRespond(withStatus(HttpStatus.NOT_FOUND)
						.contentType(MediaType.APPLICATION_JSON)
						.body("{ \"code\": \"POST_NOT_FOUND\", \"message\": \"게시물을 찾을 수 없습니다.\" }"));

		assertThatThrownBy(() -> client.registerDirectPost(100L, "ABC123", null, false))
				.isInstanceOfSatisfying(MonitoringApiException.class, e -> {
					assertThat(e.code()).isEqualTo("POST_NOT_FOUND");
					assertThat(e.httpStatus()).isEqualTo(404);
				});
	}

	@Test
	void direct_등록_422_에러_코드가_그대로_승격된다() {
		server.expect(requestTo(BASE + "/api/brands/100/direct-posts"))
				.andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY)
						.contentType(MediaType.APPLICATION_JSON)
						.body("{ \"code\": \"PRIVATE_ACCOUNT\", \"message\": \"비공개 계정입니다.\" }"));

		assertThatThrownBy(() -> client.registerDirectPost(100L, "ABC123", null, false))
				.isInstanceOfSatisfying(MonitoringApiException.class,
						e -> assertThat(e.code()).isEqualTo("PRIVATE_ACCOUNT"));
	}

	@Test
	void direct_등록_바디_없는_5xx는_Unavailable() {
		server.expect(requestTo(BASE + "/api/brands/100/direct-posts"))
				.andRespond(withStatus(HttpStatus.BAD_GATEWAY));

		assertThatThrownBy(() -> client.registerDirectPost(100L, "ABC123", null, false))
				.isInstanceOf(MonitoringUnavailableException.class);
	}

	@Test
	void direct_취소_204는_바디_없이_성공한다() {
		server.expect(requestTo(BASE + "/api/brands/100/direct-posts/ABC123"))
				.andExpect(method(HttpMethod.DELETE))
				.andRespond(withStatus(HttpStatus.NO_CONTENT));

		client.deleteDirectPost(100L, "ABC123");

		server.verify();
	}

	/**
	 * 결함 2 회귀(2026-08-18 스테이징 실측) — direct 등록 전용 타임아웃 분리 검증. 두 RestClient를
	 * 각각 다른 MockRestServiceServer에 바인딩해, registerDirectPost가 실제로 directPostRestClient로만
	 * 나가고 일반 restClient(다른 명령이 쓰는 쪽)에는 아무 요청도 안 감을 확인한다 — 전용 클라이언트
	 * 분리로 readTimeout을 30초로 넉넉히 잡을 수 있는 배선이 실제로 살아있는지의 증거.
	 */
	@Test
	void direct_등록은_전용_RestClient로만_나가고_일반_명령_클라이언트는_건드리지_않는다() {
		RestClient.Builder generalBuilder = RestClient.builder().baseUrl(BASE);
		MockRestServiceServer generalServer = MockRestServiceServer.bindTo(generalBuilder).build();
		RestClient.Builder directPostBuilder = RestClient.builder().baseUrl(BASE);
		MockRestServiceServer directPostServer = MockRestServiceServer.bindTo(directPostBuilder).build();
		MonitoringCommandClient separated =
				new MonitoringCommandClient(generalBuilder.build(), directPostBuilder.build());

		directPostServer.expect(requestTo(BASE + "/api/brands/100/direct-posts"))
				.andExpect(method(HttpMethod.POST))
				.andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON)
						.body("""
								{ "shortCode": "ABC123", "authorUsername": "creator", "takenAt": "2026-08-01T00:00:00Z",
								  "contentType": "REELS" }
								"""));

		MonitoringCommandClient.DirectPostResult result = separated.registerDirectPost(100L, "ABC123", null, false);

		assertThat(result.shortCode()).isEqualTo("ABC123");
		directPostServer.verify();
		generalServer.verify();   // 기대 0건 — 일반 restClient로는 아무 요청도 안 갔다
	}
}
