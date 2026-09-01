package com.celfit.instagram.source.self;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

	@Test
	void 프록시_URL에_포트가_없으면_SelfCrawlException으로_실패한다() {
		// InetSocketAddress(host, -1) — F2 결함(URI.create/newClient가 try 밖에 있어 그대로 새던 지점).
		ProxyConfig proxy = new ProxyConfig("http://user:pass@127.0.0.1", null, Duration.ofSeconds(5), false);
		SelfHttpClient client = new SelfHttpClient(proxy);
		assertThatThrownBy(() -> client.get("http://127.0.0.1/x", ProxyTier.RESIDENTIAL, Map.of()))
				.isInstanceOf(SelfCrawlException.class)
				.satisfies(e -> assertThat(((SelfCrawlException) e).errorClass())
						.isEqualTo(SelfErrorClass.OTHER));
	}

	@Test
	void 프록시_URL에_URI_파싱을_깨는_문자가_있으면_SelfCrawlException으로_실패한다() {
		// 비밀번호에 공백 등 URI.create가 거부하는 문자가 실린 경우(ProxyUrls javadoc이 경고하는 케이스).
		ProxyConfig proxy = new ProxyConfig("http://user:pa ss@127.0.0.1:8080", null, Duration.ofSeconds(5), false);
		SelfHttpClient client = new SelfHttpClient(proxy);
		assertThatThrownBy(() -> client.get("http://127.0.0.1/x", ProxyTier.RESIDENTIAL, Map.of()))
				.isInstanceOf(SelfCrawlException.class)
				.satisfies(e -> assertThat(((SelfCrawlException) e).errorClass())
						.isEqualTo(SelfErrorClass.OTHER));
	}
}
