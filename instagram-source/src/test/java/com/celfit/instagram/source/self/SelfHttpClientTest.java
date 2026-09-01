package com.celfit.instagram.source.self;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class SelfHttpClientTest {

	private HttpServer server;
	private final AtomicReference<String> seenUa = new AtomicReference<>();
	private final AtomicReference<String> seenAppId = new AtomicReference<>();

	@AfterEach
	void tearDown() {
		if (server != null) {
			server.stop(0);
		}
	}

	private String start(int status, String body) throws IOException {
		return start(status, body, null);
	}

	private String start(int status, String body, String setCookie) throws IOException {
		server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		server.createContext("/", ex -> {
			seenUa.set(ex.getRequestHeaders().getFirst("User-Agent"));
			seenAppId.set(ex.getRequestHeaders().getFirst("x-ig-app-id"));
			if (setCookie != null) {
				ex.getResponseHeaders().add("Set-Cookie", setCookie);
			}
			byte[] b = body.getBytes(StandardCharsets.UTF_8);
			ex.sendResponseHeaders(status, b.length);
			ex.getResponseBody().write(b);
			ex.close();
		});
		server.start();
		return "http://127.0.0.1:" + server.getAddress().getPort();
	}

	private static SelfHttpClient client() {
		return new SelfHttpClient(new ProxyConfig(null, null, Duration.ofSeconds(5), false));
	}

	@Test
	void get_200이면_본문과_헤더를_돌려준다() throws IOException {
		String base = start(200, "{\"ok\":true}");
		SelfResponse res = client().get(base + "/api/x", ProxyTier.RESIDENTIAL,
				Map.of("x-ig-app-id", "936619743392459"));
		assertThat(res.status()).isEqualTo(200);
		assertThat(res.body()).isEqualTo("{\"ok\":true}");
		assertThat(seenAppId.get()).isEqualTo("936619743392459");
		assertThat(seenUa.get()).contains("Chrome/120.0");
	}

	@Test
	void 비200_상태는_그대로_전달한다() throws IOException {
		String base = start(404, "not found");
		SelfResponse res = client().get(base + "/x", ProxyTier.RESIDENTIAL, Map.of());
		assertThat(res.status()).isEqualTo(404);
	}

	@Test
	void 응답_헤더가_대소문자_무관으로_조회된다() throws IOException {
		String base = start(200, "{}", "csrftoken=ABC123; Path=/; Secure");
		SelfResponse res = client().get(base + "/x", ProxyTier.RESIDENTIAL, Map.of());
		assertThat(res.header("set-cookie")).anyMatch(v -> v.contains("csrftoken=ABC123"));
		assertThat(res.header("Set-Cookie")).anyMatch(v -> v.contains("csrftoken=ABC123"));
	}
}
