package com.celfit.was;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP 응답 압축 검증 — 성과 대시보드 /contents는 유저에 따라 수십 MB JSON(스냅샷 전체 이력 +
 * 댓글 45건 × 수천 게시물)이라 무압축 전송이 체감 지연의 본체다(08-12 실측: 브랜드 7개 유저 기준
 * 게시물 6,931건·댓글 56,642행, Caddy·Spring 어디에도 압축 없음). 압축은 톰캣 커넥터 레벨 동작이라
 * MockMvc로는 검증 불가 — RANDOM_PORT 실 서버로 확인한다(ProdForwardedHeadersTest 관용구).
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class ResponseCompressionTest extends IntegrationTest {

	@LocalServerPort
	int port;

	/** permitAll 경로(/v1/auth/**) 아래에만 존재하는 테스트 전용 에코 — 운영 코드에는 없는 매핑. */
	@TestConfiguration
	static class BigJsonConfig {

		@RestController
		static class BigJsonController {
			/** min-response-size(기본 2KB)를 확실히 넘는 본문 — 압축 대상 판정을 보장한다. */
			@GetMapping(value = "/v1/auth/_test/big-json", produces = "application/json")
			String bigJson() {
				return "{\"data\":\"" + "x".repeat(16_384) + "\"}";
			}
		}
	}

	private HttpResponse<byte[]> call(String... headers) throws Exception {
		HttpClient client = HttpClient.newHttpClient();
		HttpRequest.Builder builder = HttpRequest.newBuilder(
				URI.create("http://127.0.0.1:" + port + "/v1/auth/_test/big-json"));
		if (headers.length > 0) {
			builder.headers(headers);
		}
		HttpResponse<byte[]> response = client.send(builder.GET().build(), HttpResponse.BodyHandlers.ofByteArray());
		assertThat(response.statusCode()).isEqualTo(200);
		return response;
	}

	@Test
	void gzip_수용_클라이언트에는_압축_응답이_나간다() throws Exception {
		HttpResponse<byte[]> response = call("Accept-Encoding", "gzip");
		assertThat(response.headers().firstValue("Content-Encoding")).hasValue("gzip");
		// 압축이 실제로 걸렸다면 본문이 원문(16KB+)보다 확연히 작아야 한다(x 반복이라 극단 압축).
		assertThat(response.body().length).isLessThan(4_096);
	}

	@Test
	void 압축_미수용_클라이언트에는_원문_그대로다() throws Exception {
		HttpResponse<byte[]> response = call();
		assertThat(response.headers().firstValue("Content-Encoding")).isEmpty();
		assertThat(response.body().length).isGreaterThan(16_000);
	}
}
