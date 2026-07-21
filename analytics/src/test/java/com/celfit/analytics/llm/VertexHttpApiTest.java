package com.celfit.analytics.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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

	static final int BIG_LINES_PER_FILE = 100_000;

	HttpServer server;
	List<String> bodies;
	List<String> paths;
	List<String> authHeaders;
	AtomicInteger status429Count;
	CountDownLatch firstLineSeen;
	AtomicBoolean serverObservedConsumption;

	@BeforeEach
	void setUp() throws Exception {
		bodies = new CopyOnWriteArrayList<>();
		paths = new CopyOnWriteArrayList<>();
		authHeaders = new CopyOnWriteArrayList<>();
		status429Count = new AtomicInteger(0);
		firstLineSeen = new CountDownLatch(1);
		serverObservedConsumption = new AtomicBoolean(false);
		server = HttpServer.create(new InetSocketAddress(0), 0);
		server.createContext("/", ex -> {
			ByteArrayOutputStream buf = new ByteArrayOutputStream();
			ex.getRequestBody().transferTo(buf);
			String uri = ex.getRequestURI().toString();
			bodies.add(buf.toString(StandardCharsets.UTF_8));
			paths.add(uri);
			authHeaders.add(ex.getRequestHeaders().getFirst("Authorization"));
			// 대용량 오브젝트 — 본문을 청크로 흘려보낸다(서버도 전체를 만들지 않음)
			if (uri.contains("predictions_big_")) {
				int fileIdx = uri.contains("big_1") ? 1 : uri.contains("big_2") ? 2 : 3;
				ex.sendResponseHeaders(200, 0);
				try (OutputStream os = ex.getResponseBody()) {
					for (int i = 0; i < BIG_LINES_PER_FILE; i++) {
						os.write(("{\"file\":" + fileIdx + ",\"n\":" + i + "}\n")
								.getBytes(StandardCharsets.UTF_8));
					}
				}
				return;
			}
			// 스트리밍 관측용 오브젝트 — 첫 줄 flush 후 소비자가 그 줄을 읽을 때까지 대기.
			// 구현이 본문 전체를 버퍼링하면 소비자는 응답 종료 전까지 첫 줄을 못 보므로
			// 여기 await가 타임아웃되고 serverObservedConsumption=false로 남는다.
			if (uri.contains("predictions_slow")) {
				ex.sendResponseHeaders(200, 0);
				OutputStream os = ex.getResponseBody();
				os.write("{\"slow\":1}\n".getBytes(StandardCharsets.UTF_8));
				os.flush();
				try {
					serverObservedConsumption.set(firstLineSeen.await(5, TimeUnit.SECONDS));
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
				os.write("{\"slow\":2}\n".getBytes(StandardCharsets.UTF_8));
				ex.close();
				return;
			}
			String response;
			int code = 200;
			int remaining = status429Count.getAndDecrement();
			if (remaining > 0) {
				response = "{}";
				code = 429;
			} else if (uri.contains("batchPredictionJobs")) {
				response = "{\"name\":\"projects/test-proj/locations/global/batchPredictionJobs/123\","
						+ "\"state\":\"JOB_STATE_PENDING\"}";
			} else if (uri.startsWith("/upload/")) {
				response = "{\"name\":\"input/backfill-1.jsonl\"}";
			} else if (uri.startsWith("/storage/") && uri.contains("pageToken=page-2")) {
				// 두 번째 페이지 — nextPageToken 없음(마지막 페이지)
				response = "{\"items\":[{\"name\":\"output/paged-1/job-9/predictions_b.jsonl\"}]}";
			} else if (uri.startsWith("/storage/") && uri.contains("prefix=output%2Fpaged-1%2Fjob-9")) {
				// 첫 페이지 — nextPageToken 있음
				response = "{\"nextPageToken\":\"page-2\",\"items\":"
						+ "[{\"name\":\"output/paged-1/job-9/predictions_a.jsonl\"}]}";
			} else if (uri.startsWith("/storage/") && uri.contains("prefix=output%2Fbig-1%2Fjob-7")) {
				response = "{\"items\":[{\"name\":\"output/big-1/job-7/predictions_big_1.jsonl\"},"
						+ "{\"name\":\"output/big-1/job-7/predictions_big_2.jsonl\"},"
						+ "{\"name\":\"output/big-1/job-7/predictions_big_3.jsonl\"}]}";
			} else if (uri.startsWith("/storage/") && uri.contains("prefix=output%2Fslow-1%2Fjob-8")) {
				response = "{\"items\":[{\"name\":\"output/slow-1/job-8/predictions_slow.jsonl\"}]}";
			} else if (uri.startsWith("/storage/") && uri.contains("prefix=")) {
				response = "{\"items\":[{\"name\":\"output/backfill-1/job-123/predictions_1.jsonl\"},"
						+ "{\"name\":\"output/backfill-1/job-123/predictions_2.jsonl\"}]}";
			} else if (uri.contains("predictions_1")) {
				response = "{\"line\":1}\n";
			} else if (uri.contains("predictions_2")) {
				response = "{\"line\":2}\n";
			} else if (uri.contains("predictions_a")) {
				response = "{\"page\":1}\n";
			} else if (uri.contains("predictions_b")) {
				response = "{\"page\":2}\n";
			} else {
				response = OK_RESPONSE;
			}
			byte[] out = response.getBytes(StandardCharsets.UTF_8);
			ex.sendResponseHeaders(code, out.length);
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

	@Test
	void 업로드는_GCS_media_업로드_후_gs_URI를_돌려준다() {
		String uri = api().uploadFile("{\"a\":1}\n".getBytes(StandardCharsets.UTF_8), "backfill-1");
		assertEquals("gs://test-bucket/input/backfill-1.jsonl", uri);
		assertEquals("/upload/storage/v1/b/test-bucket/o?uploadType=media&name=input%2Fbackfill-1.jsonl",
				paths.get(0));
		assertEquals("Bearer test-token", authHeaders.get(0));
	}

	@Test
	void 배치_생성은_GCS_입출력과_모델_경로를_싣는다() {
		String name = api().createBatch("gemini-3.1-flash-lite",
				"gs://test-bucket/input/backfill-1.jsonl", "backfill-1");
		assertEquals("projects/test-proj/locations/global/batchPredictionJobs/123", name);
		JsonNode body = new ObjectMapper().readTree(bodies.get(0));
		assertEquals("publishers/google/models/gemini-3.1-flash-lite", body.path("model").asString());
		assertEquals("jsonl", body.path("inputConfig").path("instancesFormat").asString());
		assertEquals("gs://test-bucket/input/backfill-1.jsonl",
				body.path("inputConfig").path("gcsSource").path("uris").get(0).asString());
		assertEquals("gs://test-bucket/output/backfill-1/",
				body.path("outputConfig").path("gcsDestination").path("outputUriPrefix").asString());
	}

	@Test
	void 결과_다운로드는_prefix_목록의_jsonl을_줄_단위로_순서대로_전달한다() {
		List<String> lines = new java.util.ArrayList<>();
		api().downloadResults("gs://test-bucket/output/backfill-1/job-123", lines::add);
		assertEquals(List.of("{\"line\":1}", "{\"line\":2}"), lines);
	}

	@Test
	void 결과_다운로드는_nextPageToken이_있으면_다음_페이지까지_이어서_전달한다() {
		List<String> lines = new java.util.ArrayList<>();
		api().downloadResults("gs://test-bucket/output/paged-1/job-9", lines::add);
		assertEquals(List.of("{\"page\":1}", "{\"page\":2}"), lines);
	}

	@Test
	void 대용량_결과도_전체_적재_없이_줄_단위로_모두_전달한다() {
		// 파일 3개 × 100k라인 — 소비자는 세기만 하고 보관하지 않는다(운영 119MB OOM 재발 가드)
		AtomicInteger count = new AtomicInteger();
		List<String> samples = new java.util.ArrayList<>();
		api().downloadResults("gs://test-bucket/output/big-1/job-7", line -> {
			int n = count.incrementAndGet();
			if (n == 1 || n == 3 * BIG_LINES_PER_FILE) {
				samples.add(line);
			}
		});
		assertEquals(3 * BIG_LINES_PER_FILE, count.get());
		assertEquals(List.of("{\"file\":1,\"n\":0}",
				"{\"file\":3,\"n\":" + (BIG_LINES_PER_FILE - 1) + "}"), samples);
	}

	@Test
	void 결과_라인은_응답_본문이_끝나기_전에도_소비된다() throws Exception {
		// 서버가 첫 줄 flush 후 소비 확인까지 기다린다 — 전체 버퍼링 구현이면 데드락 대신
		// 5초 타임아웃으로 풀리고 serverObservedConsumption=false로 실패한다.
		List<String> lines = new CopyOnWriteArrayList<>();
		api().downloadResults("gs://test-bucket/output/slow-1/job-8", line -> {
			lines.add(line);
			firstLineSeen.countDown();
		});
		assertEquals(List.of("{\"slow\":1}", "{\"slow\":2}"), lines);
		assertTrue(serverObservedConsumption.get(),
				"첫 줄이 본문 종료 전에 소비되지 않음 — 전체 버퍼링 의심");
	}
}
