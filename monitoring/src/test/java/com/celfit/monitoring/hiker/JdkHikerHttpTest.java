package com.celfit.monitoring.hiker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.celfit.instagram.source.HikerBadRequestException;
import com.celfit.instagram.source.HikerFetchException;
import com.celfit.instagram.source.SubjectNotFoundException;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
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
	private final AtomicInteger calls = new AtomicInteger();

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

	/** 앞선 N회는 실패하고 그 다음 성공하는 서버 — 재시도가 실제로 다시 쏘는지 본다. */
	private String startFlakyServer(int failures, int failStatus) throws IOException {
		AtomicInteger seen = new AtomicInteger();
		server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		server.createContext("/", exchange -> {
			int n = seen.incrementAndGet();
			calls.set(n);
			boolean fail = n <= failures;
			byte[] bytes = (fail ? "boom" : "{\"status\":\"ok\"}").getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(fail ? failStatus : 200, bytes.length);
			try (var out = exchange.getResponseBody()) {
				out.write(bytes);
			}
		});
		server.start();
		return "http://127.0.0.1:" + server.getAddress().getPort();
	}

	/** 기본 헬퍼는 재시도 0 — 상태코드 매핑 테스트가 백오프로 느려지지 않게 한다. */
	private static JdkHikerHttp http(String baseUrl, String apiKey) {
		return new JdkHikerHttp(new HikerProperties(apiKey, baseUrl, Duration.ofSeconds(5), 0, Duration.ZERO));
	}

	private static JdkHikerHttp retryingHttp(String baseUrl, int maxRetries) {
		return new JdkHikerHttp(new HikerProperties("test-key", baseUrl, Duration.ofSeconds(5),
				maxRetries, Duration.ZERO));
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

	/** 400은 share 해소(§2-6) 등에서 다른 실패와 구분해야 해서 별도 타입으로 던진다(HikerFetchException 상속). */
	@Test
	void _400은_HikerBadRequest() throws IOException {
		String baseUrl = startServer(400, "{\"detail\":\"bad url\"}");
		assertThatThrownBy(() -> http(baseUrl, "test-key").get("/v2/media/info/by/url?url=bad"))
				.isInstanceOf(HikerBadRequestException.class)
				.isInstanceOf(HikerFetchException.class)
				.hasMessageContaining("bad url");
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

	/**
	 * 5xx는 일시 오류라 당일 안에 다시 시도해야 한다 — 예전에는 첫 실패로 그 계정이 하루 통째로 비었다.
	 * 재시도가 없으면 스윕 하루치 지표가 조용히 구멍 난다(다음 날 스냅샷의 delta가 이틀치로 합쳐짐).
	 */
	@Test
	void _5xx는_설정된_횟수만큼_재시도하고_성공하면_본문을_준다() throws IOException {
		String baseUrl = startFlakyServer(2, 500);

		assertThat(retryingHttp(baseUrl, 2).get("/v2/user/medias?user_id=1"))
				.isEqualTo("{\"status\":\"ok\"}");
		assertThat(calls.get()).isEqualTo(3);   // 최초 1 + 재시도 2
	}

	@Test
	void 재시도를_다_써도_실패하면_마지막_오류를_던진다() throws IOException {
		String baseUrl = startFlakyServer(9, 500);

		assertThatThrownBy(() -> retryingHttp(baseUrl, 2).get("/v2/user/medias?user_id=1"))
				.isInstanceOf(HikerFetchException.class)
				.hasMessageContaining("500");
		assertThat(calls.get()).isEqualTo(3);
	}

	/** 지표 outcome 분류(TimedHikerHttp)가 상태코드에 의존한다 — HTTP 실패에는 상태코드가 실려야 한다. */
	@Test
	void HTTP_실패_예외에는_상태코드가_실린다() throws IOException {
		String baseUrl = startServer(502, "bad gateway");

		assertThatThrownBy(() -> http(baseUrl, "test-key").get("/v2/user/medias?user_id=1"))
				.isInstanceOfSatisfying(HikerFetchException.class,
						e -> assertThat(e.statusCode()).isEqualTo(502));
	}

	@Test
	void IO_실패_예외에는_상태코드가_없다() {
		// 연결 자체가 안 된 실패 — HTTP 교환이 없었으므로 상태코드 null(지표 outcome=error 근거)
		assertThatThrownBy(() -> http("http://127.0.0.1:1", "test-key").get("/v2/user/medias?user_id=1"))
				.isInstanceOfSatisfying(HikerFetchException.class,
						e -> assertThat(e.statusCode()).isNull());
	}

	/** 404는 대상 부재라 몇 번을 더 쏴도 같다 — 재시도는 콜 과금만 늘리고 종결을 늦춘다. */
	@Test
	void _404는_재시도하지_않는다() throws IOException {
		String baseUrl = startFlakyServer(9, 404);

		assertThatThrownBy(() -> retryingHttp(baseUrl, 2).get("/v2/user/by/username?username=ghost"))
				.isInstanceOf(SubjectNotFoundException.class);
		assertThat(calls.get()).isEqualTo(1);
	}
}
