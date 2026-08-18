package com.celfit.monitoring.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.celfit.common.llm.VertexHttpTransport;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * GeminiHttp seam의 Vertex 구현 — 호출부(BrandMentionJudge·AdDisclosureExtractorGemini)가 넘기는
 * AI Studio 경로가 Vertex 경로로 정확히 변환되는지 검증한다(project·location 삽입, 모델명·action 보존).
 */
class VertexGeminiHttpTest {

	HttpServer server;
	CopyOnWriteArrayList<String> paths;
	CopyOnWriteArrayList<String> bodies;

	@BeforeEach
	void setUp() throws Exception {
		paths = new CopyOnWriteArrayList<>();
		bodies = new CopyOnWriteArrayList<>();
		server = HttpServer.create(new InetSocketAddress(0), 0);
		server.createContext("/", ex -> {
			paths.add(ex.getRequestURI().toString());
			ByteArrayOutputStream buf = new ByteArrayOutputStream();
			ex.getRequestBody().transferTo(buf);
			bodies.add(buf.toString(StandardCharsets.UTF_8));
			byte[] out = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
			ex.sendResponseHeaders(200, out.length);
			ex.getResponseBody().write(out);
			ex.close();
		});
		server.start();
	}

	@AfterEach
	void tearDown() {
		server.stop(0);
	}

	VertexGeminiHttp geminiHttp(String project, String location) {
		VertexHttpTransport transport = new VertexHttpTransport(() -> "test-token",
				"http://localhost:" + server.getAddress().getPort(), 1);
		return new VertexGeminiHttp(transport, project, location);
	}

	@Test
	void AI_Studio_경로를_Vertex_경로로_변환한다() {
		String body = geminiHttp("test-proj", "global")
				.post("/v1beta/models/gemini-3.1-flash-lite:generateContent", "{\"a\":1}");
		assertThat(body).isEqualTo("{\"ok\":true}");
		assertThat(paths).containsExactly("/v1/projects/test-proj/locations/global/publishers/google/models/"
				+ "gemini-3.1-flash-lite:generateContent");
		assertThat(bodies).containsExactly("{\"a\":1}");
	}

	@Test
	void location이_다르면_경로에_그대로_반영된다() {
		geminiHttp("brand-proj", "us-central1").post("/v1beta/models/m:generateContent", "{}");
		assertThat(paths).containsExactly(
				"/v1/projects/brand-proj/locations/us-central1/publishers/google/models/m:generateContent");
	}

	@Test
	void 모델_세그먼트가_없는_경로는_예외() {
		assertThatThrownBy(() -> geminiHttp("p", "global").post("/v1beta/oops", "{}"))
				.isInstanceOf(IllegalArgumentException.class);
	}
}
