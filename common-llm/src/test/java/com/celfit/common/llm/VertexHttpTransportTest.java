package com.celfit.common.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

/**
 * Vertex 범용 전송 HTTP 계약 — analytics {@code VertexHttpApiTest}·monitoring
 * {@code GeminiHttpTransportTest}의 로컬 HttpServer 패턴 재사용. Bearer 헤더·429/5xx 백오프·
 * 재시도 소진 예외·에러 본문 절단 한도를 실제 소켓으로 검증한다.
 */
class VertexHttpTransportTest {

	HttpServer server;
	AtomicInteger callCount;
	CopyOnWriteArrayList<String> authHeaders;
	CopyOnWriteArrayList<String> paths;
	CopyOnWriteArrayList<String> requestBodies;
	int failFirstN;
	int failStatus;
	String failBody;
	String okBody;

	@BeforeEach
	void setUp() throws Exception {
		callCount = new AtomicInteger(0);
		authHeaders = new CopyOnWriteArrayList<>();
		paths = new CopyOnWriteArrayList<>();
		requestBodies = new CopyOnWriteArrayList<>();
		failFirstN = 0;
		failStatus = 429;
		failBody = "{\"error\":\"fail\"}";
		okBody = "{\"ok\":true}";
		server = HttpServer.create(new InetSocketAddress(0), 0);
		server.createContext("/", ex -> {
			int n = callCount.incrementAndGet();
			authHeaders.add(ex.getRequestHeaders().getFirst("Authorization"));
			paths.add(ex.getRequestURI().toString());
			ByteArrayOutputStream buf = new ByteArrayOutputStream();
			ex.getRequestBody().transferTo(buf);
			requestBodies.add(buf.toString(StandardCharsets.UTF_8));
			boolean fail = n <= failFirstN;
			byte[] out = (fail ? failBody : okBody).getBytes(StandardCharsets.UTF_8);
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

	VertexHttpTransport transport() {
		return transport(6, 2000);
	}

	VertexHttpTransport transport(int maxAttempts, int errorBodyLogLimit) {
		// retryBaseMillis=1 — 테스트 속도용(운영 기본은 15000ms)
		return new VertexHttpTransport(() -> "test-token",
				"http://localhost:" + server.getAddress().getPort(), 1, maxAttempts, errorBodyLogLimit);
	}

	@Test
	void 정상_응답을_그대로_반환하고_Bearer_헤더를_싣는다() {
		String body = transport().post("/v1/projects/p/locations/global/publishers/google/models/m:generateContent",
				"{}");
		assertThat(body).isEqualTo("{\"ok\":true}");
		assertThat(callCount.get()).isEqualTo(1);
		assertThat(authHeaders).containsExactly("Bearer test-token");
		assertThat(paths).containsExactly(
				"/v1/projects/p/locations/global/publishers/google/models/m:generateContent");
	}

	@Test
	void 일시_429는_재시도로_넘긴다() {
		failFirstN = 2;
		String body = transport().post("/v1/x", "{}");
		assertThat(body).isEqualTo("{\"ok\":true}");
		assertThat(callCount.get()).isEqualTo(3);
	}

	@Test
	void 재시도_소진까지_429면_쿼터_예외() {
		failFirstN = 100;
		assertThatThrownBy(() -> transport(3, 2000).post("/v1/x", "{}"))
				.isInstanceOf(LlmQuotaExhaustedException.class);
		assertThat(callCount.get()).isEqualTo(3);
	}

	@Test
	void 재시도_대상이_아닌_4xx는_즉시_실패하고_호출은_1회뿐이다() {
		failFirstN = 100;
		failStatus = 400;
		assertThatThrownBy(() -> transport().post("/v1/x", "{}"))
				.isInstanceOf(IllegalStateException.class)
				.isNotInstanceOf(LlmQuotaExhaustedException.class);
		assertThat(callCount.get()).isEqualTo(1);
	}

	@Test
	void 오백대_에러도_재시도_대상이다() {
		failFirstN = 1;
		failStatus = 503;
		String body = transport().post("/v1/x", "{}");
		assertThat(body).isEqualTo("{\"ok\":true}");
		assertThat(callCount.get()).isEqualTo(2);
	}

	@Test
	void 에러_본문_로깅_절단_한도가_넉넉하다() {
		// 08-18 429 폭주 실측 계기 — 쿼터 메트릭 이름이 잘리지 않게 절단 한도를 넉넉히(>=1000자) 잡는다
		failFirstN = 100;
		failStatus = 400;
		failBody = "x".repeat(1500);
		assertThatThrownBy(() -> transport(1, 2000).post("/v1/x", "{}"))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("x".repeat(1200)); // 1000자 이상 보존됨을 확인
	}

	@Test
	void IO_오류도_백오프_재시도한다() {
		// 연결 자체가 안 되는 포트로 보내 IOException 유발 — maxAttempts 소진까지 재시도 후 예외
		VertexHttpTransport deadTransport = new VertexHttpTransport(() -> "t", "http://localhost:1", 1, 2, 2000);
		assertThatThrownBy(() -> deadTransport.post("/v1/x", "{}")).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void 요청_본문이_그대로_전달된다() {
		transport().post("/v1/x", "{\"a\":1}");
		assertThat(requestBodies).containsExactly("{\"a\":1}");
	}

	// --- postStream(T1) ---

	@Test
	void SSE_data_라인만_콜백으로_전달하고_빈줄_주석_다른필드_DONE은_무시한다() throws Exception {
		server.createContext("/stream-ok", ex -> {
			String body = ": comment\n"
					+ "data: {\"chunk\":1}\n\n"
					+ "event: ping\n\n"
					+ "data: {\"chunk\":2}\n\n"
					+ "data: [DONE]\n\n";
			byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
			ex.sendResponseHeaders(200, bytes.length);
			ex.getResponseBody().write(bytes);
			ex.close();
		});

		List<String> received = new CopyOnWriteArrayList<>();
		transport().postStream("/stream-ok", "{\"x\":1}", received::add);

		assertThat(received).containsExactly("{\"chunk\":1}", "{\"chunk\":2}");
	}

	@Test
	void 스트림_시작_전_429는_재시도로_넘긴다() {
		failFirstN = 2;
		okBody = "data: {\"ok\":true}\n\n";
		List<String> received = new CopyOnWriteArrayList<>();

		transport().postStream("/v1/x", "{}", received::add);

		assertThat(callCount.get()).isEqualTo(3);
		assertThat(received).containsExactly("{\"ok\":true}");
	}

	@Test
	void 스트림도_재시도_소진까지_429면_쿼터_예외() {
		failFirstN = 100;
		assertThatThrownBy(() -> transport(3, 2000).postStream("/v1/x", "{}", d -> { }))
				.isInstanceOf(LlmQuotaExhaustedException.class);
		assertThat(callCount.get()).isEqualTo(3);
	}

	/**
	 * 스트림이 열려 바이트가 흐르기 시작한 뒤 연결이 끊기면 재시도하지 않고 그대로 예외를 던진다 —
	 * 이미 onData로 전달된 청크를 두고 처음부터 다시 스트리밍하면 클라이언트 화면에 답변이
	 * 중복돼 붙는다. Content-Length를 실제 바이트 수보다 크게 선언해 조기 종료(연결 끊김)를
	 * 재현한다.
	 */
	@Test
	void 스트림_시작_후_끊기면_재시도하지_않고_예외를_던진다() throws Exception {
		AtomicInteger dropCalls = new AtomicInteger();
		server.createContext("/stream-drop", ex -> {
			dropCalls.incrementAndGet();
			byte[] bytes = "data: {\"a\":1}\n\n".getBytes(StandardCharsets.UTF_8);
			// 실제보다 큰 Content-Length를 선언해 클라이언트가 더 읽으려다 연결 종료를 만나게 한다.
			ex.sendResponseHeaders(200, bytes.length + 500);
			ex.getResponseBody().write(bytes);
			ex.getResponseBody().flush();
			ex.close();
		});

		List<String> received = new CopyOnWriteArrayList<>();
		assertThatThrownBy(() -> transport().postStream("/stream-drop", "{}", received::add))
				.isInstanceOf(IllegalStateException.class);

		assertThat(received).containsExactly("{\"a\":1}");
		assertThat(dropCalls.get()).isEqualTo(1);
	}
}
