package com.celfit.analytics.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Vertex REST 계약: URL 경로·Bearer 헤더·본문(AI Studio와 동일 camelCase)·429 백오프. */
class VertexHttpApiTest {

	static final String OK_RESPONSE = """
			{"candidates":[{"content":{"parts":[{"text":"{\\"a\\":1}"}]}}],
			 "usageMetadata":{"promptTokenCount":10,"candidatesTokenCount":5}}""";

	HttpServer server;
	List<String> bodies;
	List<String> paths;
	List<String> authHeaders;
	AtomicInteger status429Count;

	@BeforeEach
	void setUp() throws Exception {
		bodies = new CopyOnWriteArrayList<>();
		paths = new CopyOnWriteArrayList<>();
		authHeaders = new CopyOnWriteArrayList<>();
		status429Count = new AtomicInteger(0);
		server = HttpServer.create(new InetSocketAddress(0), 0);
		server.createContext("/", ex -> {
			ByteArrayOutputStream buf = new ByteArrayOutputStream();
			ex.getRequestBody().transferTo(buf);
			bodies.add(buf.toString(StandardCharsets.UTF_8));
			paths.add(ex.getRequestURI().toString());
			authHeaders.add(ex.getRequestHeaders().getFirst("Authorization"));
			int remaining = status429Count.getAndDecrement();
			byte[] out = (remaining > 0 ? "{}" : OK_RESPONSE).getBytes(StandardCharsets.UTF_8);
			ex.sendResponseHeaders(remaining > 0 ? 429 : 200, out.length);
			ex.getResponseBody().write(out);
			ex.close();
		});
		server.start();
	}

	@AfterEach
	void tearDown() {
		server.stop(0);
	}

	String base() {
		return "http://localhost:" + server.getAddress().getPort();
	}

	VertexHttpApi api() {
		// storageBaseUrl은 동기 테스트에서 미사용 — 같은 로컬 서버로 지정
		return new VertexHttpApi(() -> "test-token", base(), base(),
				"test-proj", "global", "test-bucket", 1);
	}

	@Test
	void 동기_호출은_global_모델_경로와_Bearer_헤더를_쓴다() {
		api().generateJson("gemini-3.1-flash-lite", "시스템", "유저", null,
				"{\"type\":\"object\"}", 1024);
		assertEquals("/v1/projects/test-proj/locations/global/publishers/google/models/"
				+ "gemini-3.1-flash-lite:generateContent", paths.get(0));
		assertEquals("Bearer test-token", authHeaders.get(0));
	}

	@Test
	void 요청_본문은_AI_Studio와_동일_구조다() {
		api().generateJson("m", "시스템 지침", "유저 입력", null,
				"{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"integer\"}}}", 4096);
		JsonNode body = new ObjectMapper().readTree(bodies.get(0));
		assertEquals("시스템 지침", body.path("systemInstruction").path("parts").get(0).path("text").asString());
		assertEquals("application/json",
				body.path("generationConfig").path("responseMimeType").asString());
		assertEquals(4096, body.path("generationConfig").path("maxOutputTokens").asInt());
	}

	@Test
	void 응답_텍스트를_돌려준다() {
		assertEquals("{\"a\":1}",
				api().generateJson("m", "sys", "user", null, "{\"type\":\"object\"}", 1024));
	}

	@Test
	void 일시_429는_재시도로_넘긴다() {
		status429Count.set(2);
		assertEquals("{\"a\":1}",
				api().generateJson("m", "sys", "user", null, "{\"type\":\"object\"}", 1024));
		assertEquals(3, bodies.size());
	}

	@Test
	void 재시도_소진까지_429면_쿼터_예외() {
		status429Count.set(100);
		assertThrows(LlmQuotaExhaustedException.class,
				() -> api().generateJson("m", "sys", "user", null, "{\"type\":\"object\"}", 1024));
	}
}
