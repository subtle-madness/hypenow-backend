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

	@Test
	void 제외_문자열_조회는_terms를_그대로_반환한다() {
		server.expect(requestTo(BASE + "/api/brands/brand_official/hashtag-exclusions"))
				.andExpect(method(HttpMethod.GET))
				.andRespond(withSuccess("""
						{ "terms": ["리즈다", "lizda"] }
						""", MediaType.APPLICATION_JSON));

		assertThat(client.getHashtagExclusions("brand_official")).containsExactly("리즈다", "lizda");
		server.verify();
	}

	/** terms가 null·본문이 비어도 예외 없이 빈 목록으로 접는다 — 응답 계약을 신뢰하지 않는 방어. */
	@Test
	void 제외_문자열_조회는_terms가_null이면_빈_목록이다() {
		server.expect(requestTo(BASE + "/api/brands/brand_official/hashtag-exclusions"))
				.andRespond(withSuccess("""
						{ "terms": null }
						""", MediaType.APPLICATION_JSON));

		assertThat(client.getHashtagExclusions("brand_official")).isEmpty();
	}

	/**
	 * 08-11 정정 — monitoring이 빈 바디 404를 주던 구 계약에서는 MonitoringUnavailableException(503
	 * 오승격)으로 잘못 매핑됐다. 지금은 {code, message} 에러 바디가 채워져 있어 MonitoringApiException으로
	 * 정확히 승격되고, 호출부 V1ExceptionAdvice 공용 매핑이 그대로 404를 유지한다.
	 */
	@Test
	void 제외_문자열_조회_404는_MonitoringApiException으로_승격된다() {
		server.expect(requestTo(BASE + "/api/brands/gone_brand/hashtag-exclusions"))
				.andRespond(withStatus(HttpStatus.NOT_FOUND)
						.contentType(MediaType.APPLICATION_JSON)
						.body("{ \"code\": \"BRAND_NOT_FOUND\", \"message\": \"브랜드를 찾을 수 없습니다.\" }"));

		assertThatThrownBy(() -> client.getHashtagExclusions("gone_brand"))
				.isInstanceOfSatisfying(MonitoringApiException.class, e -> {
					assertThat(e.code()).isEqualTo("BRAND_NOT_FOUND");
					assertThat(e.httpStatus()).isEqualTo(404);
				});
	}

	@Test
	void 제외_문자열_교체는_terms를_그대로_전달하고_204를_받는다() {
		server.expect(requestTo(BASE + "/api/brands/brand_official/hashtag-exclusions"))
				.andExpect(method(HttpMethod.PUT))
				.andExpect(jsonPath("$.terms[0]").value("리즈다"))
				.andExpect(jsonPath("$.terms[1]").value("Lizda"))
				.andRespond(withStatus(HttpStatus.NO_CONTENT));

		assertThatCode(() -> client.putHashtagExclusions("brand_official", java.util.List.of("리즈다", "Lizda")))
				.doesNotThrowAnyException();
		server.verify();
	}

	@Test
	void 제외_문자열_교체_404는_MonitoringApiException으로_승격된다() {
		server.expect(requestTo(BASE + "/api/brands/gone_brand/hashtag-exclusions"))
				.andRespond(withStatus(HttpStatus.NOT_FOUND)
						.contentType(MediaType.APPLICATION_JSON)
						.body("{ \"code\": \"BRAND_NOT_FOUND\", \"message\": \"브랜드를 찾을 수 없습니다.\" }"));

		assertThatThrownBy(() -> client.putHashtagExclusions("gone_brand", java.util.List.of("리즈다")))
				.isInstanceOfSatisfying(MonitoringApiException.class, e -> {
					assertThat(e.code()).isEqualTo("BRAND_NOT_FOUND");
					assertThat(e.httpStatus()).isEqualTo(404);
				});
	}

	/** 빈 목록 교체는 monitoring이 422(code VALIDATION)로 거부한다(비소급 오염 방지, 계약 §8). */
	@Test
	void 제외_문자열_교체_빈_목록_422는_MonitoringApiException으로_승격된다() {
		server.expect(requestTo(BASE + "/api/brands/brand_official/hashtag-exclusions"))
				.andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY)
						.contentType(MediaType.APPLICATION_JSON)
						.body("{ \"code\": \"VALIDATION\", \"message\": \"제외 문자열은 최소 1개 필요합니다.\" }"));

		assertThatThrownBy(() -> client.putHashtagExclusions("brand_official", java.util.List.of()))
				.isInstanceOfSatisfying(MonitoringApiException.class, e -> {
					assertThat(e.code()).isEqualTo("VALIDATION");
					assertThat(e.httpStatus()).isEqualTo(422);
				});
	}
}
