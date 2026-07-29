package com.celfit.monitoring.hiker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 상태코드 → 예외 매핑 고정. 임시 포트에 JDK 내장 HttpServer를 띄워 실제 HTTP를 태운다
 * (404는 대상 부재라 재시도 무의미, 그 외는 재시도 여지가 있어 호출자의 처리가 갈린다).
 */
class JdkHikerHttpTest {

	private HttpServer server;
	private final AtomicReference<String> seenKey = new AtomicReference<>();
	private final AtomicReference<String> seenPath = new AtomicReference<>();

	@AfterEach
	void tearDown() {
		if (server != null) {
			server.stop(0);
		}
	}

	private String startServer(int status, String body) throws IOException {
		server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		server.createContext("/", exchange -> {
			seenKey.set(exchange.getRequestHeaders().getFirst("x-access-key"));
			seenPath.set(exchange.getRequestURI().toString());
			byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(status, bytes.length);
			try (var out = exchange.getResponseBody()) {
				out.write(bytes);
			}
		});
		server.start();
		return "http://127.0.0.1:" + server.getAddress().getPort();
	}

	private static JdkHikerHttp http(String baseUrl, String apiKey) {
		return new JdkHikerHttp(new HikerProperties(apiKey, baseUrl, Duration.ofSeconds(5)));
	}

	@Test
	void _200이면_본문을_그대로_돌려주고_인증헤더를_붙인다() throws IOException {
		String baseUrl = startServer(200, "{\"status\":\"ok\"}");
		assertThat(http(baseUrl, "test-key").get("/v2/user/by/username?username=rarebeauty"))
				.isEqualTo("{\"status\":\"ok\"}");
		assertThat(seenKey.get()).isEqualTo("test-key");
		assertThat(seenPath.get()).isEqualTo("/v2/user/by/username?username=rarebeauty");
	}

	@Test
	void _404는_SubjectNotFound() throws IOException {
		String baseUrl = startServer(404, "{\"detail\":\"not found\"}");
		assertThatThrownBy(() -> http(baseUrl, "test-key").get("/v2/user/by/username?username=ghost"))
				.isInstanceOf(SubjectNotFoundException.class)
				.hasMessageContaining("not found");
	}

	@Test
	void _500은_HikerFetch() throws IOException {
		String baseUrl = startServer(500, "boom");
		assertThatThrownBy(() -> http(baseUrl, "test-key").get("/v2/user/medias?user_id=1"))
				.isInstanceOf(HikerFetchException.class)
				.hasMessageContaining("500");
	}

	@Test
	void 키가_비면_요청을_쏘지도_않고_HikerFetch() {
		assertThatThrownBy(() -> http("http://127.0.0.1:1", "  ").get("/v2/user/medias?user_id=1"))
				.isInstanceOf(HikerFetchException.class)
				.hasMessageContaining("HIKER_API_KEY");
	}
}
