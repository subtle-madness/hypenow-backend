package com.celfit.was;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 운영(Caddy 뒤) 프록시 헤더 신뢰 검증 — application-prod.yml의 forward-headers-strategy가
 * getRemoteAddr()에 실제 클라이언트 IP(X-Forwarded-For)를 반영하는지 실 톰캣 기동으로 확인.
 * 레이트리밋 키가 remoteAddr 기반이라 이 동작이 깨지면 전 사용자가 Caddy IP 버킷 하나를 공유한다.
 * MockMvc는 톰캣 밸브(RemoteIpValve)를 타지 않아 RANDOM_PORT 실 서버로 검증한다.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("prod")
class ProdForwardedHeadersTest extends IntegrationTest {

	@LocalServerPort
	int port;

	/** permitAll 경로(/v1/auth/**) 아래에만 존재하는 테스트 전용 에코 — 운영 코드에는 없는 매핑. */
	@TestConfiguration
	static class EchoConfig {

		@RestController
		static class RemoteAddrEchoController {
			@GetMapping("/v1/auth/_test/remote-addr")
			String echo(HttpServletRequest request) {
				return request.getRemoteAddr() + "|" + request.getScheme();
			}
		}
	}

	private String call(String... headers) throws Exception {
		HttpClient client = HttpClient.newHttpClient();
		HttpRequest.Builder builder = HttpRequest.newBuilder(
				URI.create("http://127.0.0.1:" + port + "/v1/auth/_test/remote-addr"));
		if (headers.length > 0) {
			builder.headers(headers);
		}
		HttpResponse<String> response = client.send(builder.GET().build(), HttpResponse.BodyHandlers.ofString());
		assertThat(response.statusCode()).isEqualTo(200);
		return response.body();
	}

	@Test
	void 프록시가_보낸_XForwardedFor의_클라이언트_IP가_remoteAddr로_보인다() throws Exception {
		// ForwardedHeaderFilter(framework 전략)는 헤더를 무조건 신뢰 — 스푸핑 방어는 Caddy가
		// 외부 유입 X-Forwarded-For를 버리고 실측 IP로 재설정하는 것에 의존(application-prod.yml 주석)
		String body = call("X-Forwarded-For", "203.0.113.7");
		assertThat(body).startsWith("203.0.113.7|");
	}

	@Test
	void 헤더_없는_직접_요청은_소켓_IP_그대로다() throws Exception {
		String body = call();
		assertThat(body).startsWith("127.0.0.1|");
	}

	@Test
	void XForwardedProto가_https면_scheme_판정도_https다() throws Exception {
		// framework→native 전환에서 Secure 쿠키·scheme 판정(기존 주석의 목적)이 유지되는지 회귀 방지
		String body = call("X-Forwarded-For", "203.0.113.7", "X-Forwarded-Proto", "https");
		assertThat(body).isEqualTo("203.0.113.7|https");
	}
}
