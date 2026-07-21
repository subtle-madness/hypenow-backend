package com.celfit.analytics.archive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** PAR PUT 계약: 경로 결합·Content-Type/Cache-Control 헤더 전달·비2xx 실패. */
class ParImageStoreTest {

	HttpServer server;
	Map<String, String> captured = new ConcurrentHashMap<>();
	int status = 200;

	@BeforeEach
	void startServer() throws IOException {
		server = HttpServer.create(new InetSocketAddress(0), 0);
		server.createContext("/p/tok/n/ns/b/bk/o/", exchange -> {
			captured.put("method", exchange.getRequestMethod());
			captured.put("path", exchange.getRequestURI().getPath());
			captured.put("contentType", exchange.getRequestHeaders().getFirst("Content-Type"));
			captured.put("cacheControl", exchange.getRequestHeaders().getFirst("Cache-Control"));
			captured.put("body", new String(exchange.getRequestBody().readAllBytes()));
			exchange.sendResponseHeaders(status, -1);
			exchange.close();
		});
		server.start();
	}

	@AfterEach
	void stopServer() {
		server.stop(0);
	}

	String baseUrl() {
		return "http://localhost:" + server.getAddress().getPort() + "/p/tok/n/ns/b/bk/o/";
	}

	@Test
	void PUT_경로와_헤더를_전달한다() {
		new ParImageStore(baseUrl()).put("thumb/abc123.jpg", "img".getBytes(),
				"image/jpeg", "public, max-age=31536000, immutable");
		assertThat(captured.get("method")).isEqualTo("PUT");
		assertThat(captured.get("path")).isEqualTo("/p/tok/n/ns/b/bk/o/thumb/abc123.jpg");
		assertThat(captured.get("contentType")).isEqualTo("image/jpeg");
		assertThat(captured.get("cacheControl")).isEqualTo("public, max-age=31536000, immutable");
		assertThat(captured.get("body")).isEqualTo("img");
	}

	@Test
	void 비2xx면_예외() {
		status = 500;
		assertThatThrownBy(() -> new ParImageStore(baseUrl()).put("a.jpg", new byte[0], "image/jpeg", "no-cache"))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("500");
	}

	@Test
	void PAR_URL_미설정이면_생성자에서_실패() {
		assertThatThrownBy(() -> new ParImageStore(""))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("image-par-url");
	}

	@Test
	void 슬래시_없는_baseUrl도_정규화() {
		new ParImageStore(baseUrl().substring(0, baseUrl().length() - 1))
				.put("thumb/x.jpg", new byte[0], "image/jpeg", "no-cache");
		assertThat(captured.get("path")).isEqualTo("/p/tok/n/ns/b/bk/o/thumb/x.jpg");
	}
}
