package com.celfit.was.v1.brandmonitoring.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.common.llm.VertexHttpTransport;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Vertex generateContent 경로 조립 검증 - 실 소켓(HttpServer)을 띄워 common-llm 전송까지 통과시킨다
 * (common-llm VertexHttpTransportTest·monitoring GeminiHttpTransportTest와 같은 관용구:
 * Spring 컨텍스트 없이 생성자 직접 주입).
 */
class VertexChatTransportTest {

	private HttpServer server;
	private final List<String> paths = new CopyOnWriteArrayList<>();
	private final List<String> bodies = new CopyOnWriteArrayList<>();
	private int port;

	@BeforeEach
	void startServer() throws IOException {
		server = HttpServer.create(new InetSocketAddress(0), 0);
		server.createContext("/", exchange -> {
			paths.add(exchange.getRequestURI().getPath());
			try (InputStream in = exchange.getRequestBody()) {
				bodies.add(new String(in.readAllBytes(), StandardCharsets.UTF_8));
			}
			byte[] out = "{\"candidates\":[]}".getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, out.length);
			exchange.getResponseBody().write(out);
			exchange.close();
		});
		server.start();
		port = server.getAddress().getPort();
	}

	@AfterEach
	void stopServer() {
		server.stop(0);
	}

	@Test
	void 프로젝트_로케이션_모델로_generateContent_경로를_만든다() {
		VertexHttpTransport http = new VertexHttpTransport(
				() -> "test-token", "http://localhost:" + port, 10, 1, 200);
		VertexChatTransport transport =
				new VertexChatTransport(http, "hypenow-prod", "global", "gemini-2.5-flash");

		String response = transport.post("{\"contents\":[]}");

		assertThat(response).isEqualTo("{\"candidates\":[]}");
		assertThat(paths).containsExactly(
				"/v1/projects/hypenow-prod/locations/global/publishers/google/models/gemini-2.5-flash:generateContent");
		assertThat(bodies).containsExactly("{\"contents\":[]}");
	}
}
