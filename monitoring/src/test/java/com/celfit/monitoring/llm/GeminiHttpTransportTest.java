package com.celfit.monitoring.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Gemini 실전송 HTTP 계약 — analytics {@code GeminiHttpApiTest}의 로컬 HttpServer 패턴 재사용.
 * 200 정상 반환·429 백오프 재시도·재시도 소진 예외·4xx 즉시 실패(재시도 없음)를 실제 소켓으로 검증한다.
 */
class GeminiHttpTransportTest {

	HttpServer server;
	AtomicInteger callCount;
	CopyOnWriteArrayList<String> receivedApiKeyHeaders;
	int failFirstN;     // 처음 N회는 failStatus 응답, 이후는 200
	int failStatus;

	@BeforeEach
	void setUp() throws Exception {
		callCount = new AtomicInteger(0);
		receivedApiKeyHeaders = new CopyOnWriteArrayList<>();
		failFirstN = 0;
		failStatus = 429;
		server = HttpServer.create(new InetSocketAddress(0), 0);
		server.createContext("/", ex -> {
			int n = callCount.incrementAndGet();
			receivedApiKeyHeaders.add(ex.getRequestHeaders().getFirst("x-goog-api-key"));
			ex.getRequestBody().transferTo(new ByteArrayOutputStream());
			boolean fail = n <= failFirstN;
			byte[] out = (fail ? "{\"error\":\"fail\"}" : "{\"ok\":true}").getBytes(StandardCharsets.UTF_8);
			ex.sendResponseHeaders(fail ? failStatus : 200, out.length);
			ex.getResponseBody().write(out);
			ex.close();
		});
		server.start();
	}

	@AfterEach
	void tearDown() {
		server.stop(0);
	}

	GeminiHttpTransport transport() {
		// retryBaseMillis=1 — 테스트 속도용(운영 기본은 1000ms)
		return new GeminiHttpTransport("test-key", "http://localhost:" + server.getAddress().getPort(), 1);
	}

	@Test
	void 정상_응답_200을_그대로_반환한다() {
		String body = transport().post("/v1beta/models/m:generateContent", "{}");
		assertThat(body).isEqualTo("{\"ok\":true}");
		assertThat(callCount.get()).isEqualTo(1);
	}

	@Test
	void 재시도_429_이후_성공하고_매회_api_key_헤더가_실린다() {
		failFirstN = 1;
		failStatus = 429;
		String body = transport().post("/v1beta/models/m:generateContent", "{}");
		assertThat(body).isEqualTo("{\"ok\":true}");
		assertThat(callCount.get()).isEqualTo(2);
		assertThat(receivedApiKeyHeaders).containsExactly("test-key", "test-key");
	}

	@Test
	void 재시도_소진까지_429면_예외를_던진다() {
		failFirstN = 100;
		failStatus = 429;
		assertThatThrownBy(() -> transport().post("/v1beta/models/m:generateContent", "{}"))
				.isInstanceOf(IllegalStateException.class);
		assertThat(callCount.get()).isEqualTo(3);   // MAX_ATTEMPTS
	}

	@Test
	void 재시도_대상이_아닌_4xx는_즉시_실패하고_호출은_1회뿐이다() {
		failFirstN = 100;
		failStatus = 400;
		assertThatThrownBy(() -> transport().post("/v1beta/models/m:generateContent", "{}"))
				.isInstanceOf(IllegalStateException.class);
		assertThat(callCount.get()).isEqualTo(1);
	}
}
