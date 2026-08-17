package com.celfit.was.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * 브랜드 명령 seam 검증 — 레거시 명령(MonitoringCommandClientTest)은 손대지 않고 브랜드 경로만
 * 따로 검증한다.
 */
class MonitoringBrandCommandClientTest {

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
	void 브랜드_등록_요청과_응답_파싱() {
		server.expect(requestTo(BASE + "/api/brands"))
				.andExpect(method(HttpMethod.POST))
				.andExpect(jsonPath("$.username").value("brand_official"))
				.andExpect(jsonPath("$.brandName").value("브랜드코퍼레이션"))
				.andExpect(jsonPath("$.collectionMonths").value(3))
				.andRespond(withStatus(HttpStatus.CREATED)
						.contentType(MediaType.APPLICATION_JSON)
						.body("""
								{ "brandId": 42, "username": "brand_official", "followers": 12345, "status": "ACTIVE" }
								"""));

		MonitoringCommandClient.BrandRegisterResult result = client.registerBrand("brand_official", "브랜드코퍼레이션", 3);

		assertThat(result.brandId()).isEqualTo(42L);
		assertThat(result.username()).isEqualTo("brand_official");
		assertThat(result.followers()).isEqualTo(12345L);
		assertThat(result.status()).isEqualTo("ACTIVE");
		server.verify();
	}

	/** 재등록(replay)은 monitoring이 200으로 같은 바디를 준다 — was는 201과 구분하지 않는다. */
	@Test
	void 브랜드_재등록은_200_replay도_같은_결과로_읽는다() {
		server.expect(requestTo(BASE + "/api/brands"))
				.andExpect(jsonPath("$.brandName").value(nullValue()))
				.andRespond(withSuccess("""
						{ "brandId": 42, "username": "brand_official", "followers": null, "status": "ACTIVE" }
						""", MediaType.APPLICATION_JSON));

		MonitoringCommandClient.BrandRegisterResult result = client.registerBrand("brand_official", null, 12);

		assertThat(result.brandId()).isEqualTo(42L);
		assertThat(result.followers()).isNull();
	}

	@Test
	void 브랜드_등록_에러_코드는_그대로_승격된다() {
		server.expect(requestTo(BASE + "/api/brands"))
				.andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY)
						.contentType(MediaType.APPLICATION_JSON)
						.body("{ \"code\": \"PRIVATE_ACCOUNT\", \"message\": \"비공개 계정\" }"));

		assertThatThrownBy(() -> client.registerBrand("private_brand", null, 12))
				.isInstanceOfSatisfying(MonitoringApiException.class, e -> {
					assertThat(e.code()).isEqualTo("PRIVATE_ACCOUNT");
					assertThat(e.httpStatus()).isEqualTo(422);
				});
	}

	@Test
	void 브랜드_등록_접속_실패는_Unavailable() {
		server.expect(requestTo(BASE + "/api/brands"))
				.andRespond(withException(new java.net.SocketTimeoutException("read timeout")));

		assertThatThrownBy(() -> client.registerBrand("brand_official", null, 12))
				.isInstanceOf(MonitoringUnavailableException.class);
	}

	@Test
	void 브랜드_탈퇴_204는_정상_종료() {
		server.expect(requestTo(BASE + "/api/brands/brand_official"))
				.andExpect(method(HttpMethod.DELETE))
				.andRespond(withStatus(HttpStatus.NO_CONTENT));

		assertThatCode(() -> client.deregisterBrand("brand_official")).doesNotThrowAnyException();
		server.verify();
	}

	/**
	 * monitoring의 탈퇴 404는 {@code ResponseEntity.notFound()} — 바디가 비어 있어 에러 코드가 없다.
	 * 그래서 exchange()의 기본 승격(바디 해석 불가 → Unavailable)에 걸리기 전에 삼켜야 한다.
	 */
	@Test
	void 브랜드_탈퇴_404는_바디가_없어도_삼킨다() {
		server.expect(requestTo(BASE + "/api/brands/gone_brand"))
				.andRespond(withStatus(HttpStatus.NOT_FOUND));

		assertThatCode(() -> client.deregisterBrand("gone_brand")).doesNotThrowAnyException();
		server.verify();
	}

	@Test
	void 브랜드_탈퇴_5xx는_승격된다() {
		server.expect(requestTo(BASE + "/api/brands/brand_official"))
				.andRespond(withStatus(HttpStatus.BAD_GATEWAY));

		assertThatThrownBy(() -> client.deregisterBrand("brand_official"))
				.isInstanceOf(MonitoringUnavailableException.class);
	}

	@Test
	void 브랜드_탈퇴_접속_실패는_Unavailable() {
		server.expect(requestTo(BASE + "/api/brands/brand_official"))
				.andRespond(withException(new java.net.SocketTimeoutException("read timeout")));

		assertThatThrownBy(() -> client.deregisterBrand("brand_official"))
				.isInstanceOf(MonitoringUnavailableException.class);
	}

	// ---------- 태그 셋 관리(유저 입력, 2026-08-12) ----------

	@Test
	void 태그_조회는_tags를_그대로_반환한다() {
		server.expect(requestTo(BASE + "/api/brands/brand_official/hashtag-tags"))
				.andExpect(method(HttpMethod.GET))
				.andRespond(withSuccess("""
						{ "tags": ["리즈다", "lizda"] }
						""", MediaType.APPLICATION_JSON));

		assertThat(client.getHashtagTags("brand_official")).containsExactly("리즈다", "lizda");
		server.verify();
	}

	/** tags가 null·본문이 비어도 예외 없이 빈 목록으로 접는다 — 응답 계약을 신뢰하지 않는 방어. */
	@Test
	void 태그_조회는_tags가_null이면_빈_목록이다() {
		server.expect(requestTo(BASE + "/api/brands/brand_official/hashtag-tags"))
				.andRespond(withSuccess("""
						{ "tags": null }
						""", MediaType.APPLICATION_JSON));

		assertThat(client.getHashtagTags("brand_official")).isEmpty();
	}

	@Test
	void 태그_조회_404는_MonitoringApiException으로_승격된다() {
		server.expect(requestTo(BASE + "/api/brands/gone_brand/hashtag-tags"))
				.andRespond(withStatus(HttpStatus.NOT_FOUND)
						.contentType(MediaType.APPLICATION_JSON)
						.body("{ \"code\": \"BRAND_NOT_FOUND\", \"message\": \"브랜드를 찾을 수 없습니다.\" }"));

		assertThatThrownBy(() -> client.getHashtagTags("gone_brand"))
				.isInstanceOfSatisfying(MonitoringApiException.class, e -> {
					assertThat(e.code()).isEqualTo("BRAND_NOT_FOUND");
					assertThat(e.httpStatus()).isEqualTo(404);
				});
	}

	@Test
	void 태그_교체는_tags를_그대로_전달하고_204를_받는다() {
		server.expect(requestTo(BASE + "/api/brands/brand_official/hashtag-tags"))
				.andExpect(method(HttpMethod.PUT))
				.andExpect(jsonPath("$.tags[0]").value("리즈다"))
				.andExpect(jsonPath("$.tags[1]").value("Lizda"))
				.andRespond(withStatus(HttpStatus.NO_CONTENT));

		assertThatCode(() -> client.putHashtagTags("brand_official", java.util.List.of("리즈다", "Lizda")))
				.doesNotThrowAnyException();
		server.verify();
	}

	@Test
	void 태그_교체_404는_MonitoringApiException으로_승격된다() {
		server.expect(requestTo(BASE + "/api/brands/gone_brand/hashtag-tags"))
				.andRespond(withStatus(HttpStatus.NOT_FOUND)
						.contentType(MediaType.APPLICATION_JSON)
						.body("{ \"code\": \"BRAND_NOT_FOUND\", \"message\": \"브랜드를 찾을 수 없습니다.\" }"));

		assertThatThrownBy(() -> client.putHashtagTags("gone_brand", java.util.List.of("리즈다")))
				.isInstanceOfSatisfying(MonitoringApiException.class, e -> {
					assertThat(e.code()).isEqualTo("BRAND_NOT_FOUND");
					assertThat(e.httpStatus()).isEqualTo(404);
				});
	}

	/**
	 * 2026-08-12부터 PUT 빈 목록은 monitoring이 그대로 204로 받아준다(구 하한 가드 폐지) — 브랜드
	 * 태그 감지 전체를 끄는 것과 같다.
	 */
	@Test
	void 태그_교체_빈_목록도_204를_받는다() {
		server.expect(requestTo(BASE + "/api/brands/brand_official/hashtag-tags"))
				.andExpect(method(HttpMethod.PUT))
				.andExpect(jsonPath("$.tags").isEmpty())
				.andRespond(withStatus(HttpStatus.NO_CONTENT));

		assertThatCode(() -> client.putHashtagTags("brand_official", java.util.List.of()))
				.doesNotThrowAnyException();
		server.verify();
	}

	// ---------- 태그 단건 추가·삭제(2026-08-12, 표준 REST 확장) ----------

	@Test
	void 태그_추가는_tags를_그대로_전달하고_204를_받는다() {
		server.expect(requestTo(BASE + "/api/brands/brand_official/hashtag-tags"))
				.andExpect(method(HttpMethod.POST))
				.andExpect(jsonPath("$.tags[0]").value("리즈다"))
				.andRespond(withStatus(HttpStatus.NO_CONTENT));

		assertThatCode(() -> client.addHashtagTags("brand_official", java.util.List.of("리즈다")))
				.doesNotThrowAnyException();
		server.verify();
	}

	/** POST는 PUT과 달리 빈 입력을 monitoring이 422로 거부한다(계약 §8-3-1) — 클라이언트는 그대로 승격만. */
	@Test
	void 태그_추가_빈_목록_422는_MonitoringApiException으로_승격된다() {
		server.expect(requestTo(BASE + "/api/brands/brand_official/hashtag-tags"))
				.andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY)
						.contentType(MediaType.APPLICATION_JSON)
						.body("{ \"code\": \"VALIDATION\", \"message\": \"추가할 태그가 없습니다.\" }"));

		assertThatThrownBy(() -> client.addHashtagTags("brand_official", java.util.List.of()))
				.isInstanceOfSatisfying(MonitoringApiException.class, e -> {
					assertThat(e.code()).isEqualTo("VALIDATION");
					assertThat(e.httpStatus()).isEqualTo(422);
				});
	}

	@Test
	void 태그_단건_삭제는_204를_받는다() {
		server.expect(requestTo(BASE + "/api/brands/brand_official/hashtag-tags/lizda"))
				.andExpect(method(HttpMethod.DELETE))
				.andRespond(withStatus(HttpStatus.NO_CONTENT));

		assertThatCode(() -> client.deleteHashtagTag("brand_official", "lizda"))
				.doesNotThrowAnyException();
		server.verify();
	}

	/**
	 * 한글 태그 경로 변수 왕복 — RestClient uri 템플릿이 자동으로 percent-encoding하므로 서버가
	 * 받는 실제 요청 경로는 UTF-8 percent-encoded 문자열이다. MockRestServiceServer의 requestTo는
	 * 인코딩된 URI로 매칭해야 한다(디코딩된 한글 문자열로 매칭하면 요청이 안 잡힌다).
	 */
	@Test
	void 태그_단건_삭제는_한글_태그도_인코딩되어_왕복한다() {
		server.expect(requestTo(BASE + "/api/brands/brand_official/hashtag-tags/%EB%81%8C%EB%A6%AC%EB%A9%94"))
				.andExpect(method(HttpMethod.DELETE))
				.andRespond(withStatus(HttpStatus.NO_CONTENT));

		assertThatCode(() -> client.deleteHashtagTag("brand_official", "끌리메"))
				.doesNotThrowAnyException();
		server.verify();
	}

	@Test
	void 태그_전체_삭제는_204를_받는다() {
		server.expect(requestTo(BASE + "/api/brands/brand_official/hashtag-tags"))
				.andExpect(method(HttpMethod.DELETE))
				.andRespond(withStatus(HttpStatus.NO_CONTENT));

		assertThatCode(() -> client.deleteAllHashtagTags("brand_official"))
				.doesNotThrowAnyException();
		server.verify();
	}
}
