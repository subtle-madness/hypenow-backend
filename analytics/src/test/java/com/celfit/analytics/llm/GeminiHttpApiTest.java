package com.celfit.analytics.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

/** Gemini REST 호출 계약: 본문 구조(responseSchema 포함)·응답 텍스트 추출·429 백오프·소진 시 쿼터 예외. */
class GeminiHttpApiTest {

	static final String OK_RESPONSE = """
			{"candidates":[{"content":{"parts":[{"text":"{\\"a\\":1}"}]}}],
			 "usageMetadata":{"promptTokenCount":10,"candidatesTokenCount":5}}""";

	HttpServer server;
	List<String> bodies;
	AtomicInteger status429Count;

	@BeforeEach
	void setUp() throws Exception {
		bodies = new CopyOnWriteArrayList<>();
		status429Count = new AtomicInteger(0);
		server = HttpServer.create(new InetSocketAddress(0), 0);
		server.createContext("/", ex -> {
			ByteArrayOutputStream buf = new ByteArrayOutputStream();
			ex.getRequestBody().transferTo(buf);
			bodies.add(buf.toString(StandardCharsets.UTF_8));
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

	GeminiHttpApi api() {
		// rpm 매우 크게(페이싱 대기 0)·재시도 기본 대기 1ms — 테스트 속도용
		return new GeminiHttpApi("test-key", "http://localhost:" + server.getAddress().getPort(),
				600_000, 1);
	}

	@Test
	void 요청_본문에_시스템_스키마_생성설정이_실린다() {
		api().generateJson("gemini-3.1-flash-lite", "시스템 지침", "유저 입력", null,
				"{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"integer\"}}}", 4096);
		JsonNode body = new ObjectMapper().readTree(bodies.get(0));
		assertEquals("시스템 지침", body.path("systemInstruction").path("parts").get(0).path("text").asString());
		assertEquals("유저 입력", body.path("contents").get(0).path("parts").get(0).path("text").asString());
		JsonNode gen = body.path("generationConfig");
		assertEquals("application/json", gen.path("responseMimeType").asString());
		assertEquals("object", gen.path("responseSchema").path("type").asString());
		assertEquals(4096, gen.path("maxOutputTokens").asInt());
		assertEquals(0, gen.path("temperature").asInt());
	}

	@Test
	void 이미지가_있으면_inlineData_파트가_텍스트_앞에_실린다() {
		api().generateJson("m", "sys", "user", new GeminiApi.InlineImage("image/jpeg", new byte[] {1, 2}),
				"{\"type\":\"object\"}", 1024);
		JsonNode parts = new ObjectMapper().readTree(bodies.get(0)).path("contents").get(0).path("parts");
		assertEquals("image/jpeg", parts.get(0).path("inlineData").path("mimeType").asString());
		assertTrue(parts.get(1).path("text").asString().contains("user"));
	}

	@Test
	void 응답_텍스트를_돌려준다() {
		String out = api().generateJson("m", "sys", "user", null, "{\"type\":\"object\"}", 1024);
		assertEquals("{\"a\":1}", out);
	}

	@Test
	void 일시_429는_재시도로_넘긴다() {
		status429Count.set(2);
		String out = api().generateJson("m", "sys", "user", null, "{\"type\":\"object\"}", 1024);
		assertEquals("{\"a\":1}", out);
		assertEquals(3, bodies.size());
	}

	@Test
	void 재시도_소진까지_429면_쿼터_예외() {
		status429Count.set(100);
		assertThrows(LlmQuotaExhaustedException.class,
				() -> api().generateJson("m", "sys", "user", null, "{\"type\":\"object\"}", 1024));
	}
}
