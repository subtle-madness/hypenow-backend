# Vertex AI 전환 + 최근 12개 백필 자격 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

> 상태: ✅ 실행됨(07-30 — 백필 총 16,827건 완료. 운영 런북: [runbooks/2026-07-20-vertex-backfill-runbook.md](../../../runbooks/2026-07-20-vertex-backfill-runbook.md)) · 스펙: [2026-07-20-vertex-migration-recent12-backfill-design.md](../../specs/2026-07-20-vertex-migration-recent12-backfill-design.md)

**Goal:** analytics LLM 경로(동기+배치)를 Vertex AI(서비스 계정 OAuth, global 엔드포인트)로 전환하고, 분석 자격을 "최근 N개(기본 12) 윈도우 포함" OR 조건으로 확장해 백필을 재도입한다.

**Architecture:** 새 `VertexHttpApi`가 기존 `GeminiApi`·`GeminiBatchApi` 인터페이스를 구현(순수 JDK HTTP + `google-auth-library-oauth2-http`). 프롬프트·파서·잡 로직 무접촉, 배선은 `analytics.llm-provider=vertex`. 백필 상관관계는 Vertex 배치 출력의 request 에코 첫 줄(`콘텐츠: {shortCode}`) 파싱. 자격 확장은 04 뷰와 `ContentAnalysisJob` SQL의 OR 분기 + `timely`/`late_backfill` 마킹.

**Tech Stack:** Java 21, Spring Boot 4.1, 순수 `java.net.http`, `com.google.auth:google-auth-library-oauth2-http:1.49.0`, PostgreSQL SQL 하니스.

**작업 위치:** 워크트리 `.worktrees/vertex`, 브랜치 `feat/vertex-migration` (origin/develop 기준). 커밋 메시지 한국어, prefix `feat(analytics):`/`docs:`.

**검증 유보 항목(실 스모크에서 확정 — 코드에 TODO 금지, 주석으로 근거 표기):**
- batch `model` 필드: `publishers/google/models/{id}` 짧은 형식 우선, 실패 시 풀 경로 폴백은 스모크에서 수동 확인
- GCS 버킷 리전 제약(us 멀티리전 권장), Vertex 배치 출력 파일 명명 규칙

---

### Task 1: 의존성 + AnalyticsSettings 확장

**Files:**
- Modify: `analytics/build.gradle`
- Modify: `analytics/src/main/java/com/celfit/analytics/config/AnalyticsSettings.java`

- [ ] **Step 1: build.gradle에 인증 라이브러리 추가**

`analytics/build.gradle`의 `dependencies` 블록, `implementation 'com.anthropic:anthropic-java:2.34.0'` 다음 줄에:

```gradle
	implementation 'com.google.auth:google-auth-library-oauth2-http:1.49.0'
```

- [ ] **Step 2: AnalyticsSettings에 키 4개 + 게터 추가**

키 상수 블록(`KEY_GEMINI_RPM` 아래)에 추가:

```java
	/** Vertex AI GCP 프로젝트 ID — provider=vertex일 때 필수. */
	public static final String KEY_VERTEX_PROJECT = "analytics.vertex-project";
	/** Vertex AI 로케이션 — gemini-3.1-flash-lite는 global/us/eu만 제공(도쿄 없음), 기본 global. */
	public static final String KEY_VERTEX_LOCATION = "analytics.vertex-location";
	/** Vertex 배치 입출력 GCS 버킷 이름(gs:// 없이) — 백필 배치 전용. */
	public static final String KEY_VERTEX_GCS_BUCKET = "analytics.vertex-gcs-bucket";
	/** 최근 N개 윈도우 — 01 뷰(v_recent_content)와 공유하는 키. 분석 자격 OR 분기에서 사용. */
	public static final String KEY_RECENT_WINDOW = "analytics.recent-window";
```

기본값 상수 블록(`DEFAULT_GEMINI_RPM` 아래)에 추가:

```java
	static final String DEFAULT_VERTEX_LOCATION = "global";
	static final int DEFAULT_RECENT_WINDOW = 12;
```

게터(`geminiRpm()` 아래)에 추가:

```java
	/** provider=vertex일 때만 호출됨 — 미설정이면 배선 시점에 fail-fast. */
	public String vertexProject() {
		return read(KEY_VERTEX_PROJECT).orElseThrow(() -> new IllegalStateException(
				KEY_VERTEX_PROJECT + " 미설정 — app_setting에 GCP 프로젝트 ID 등록 필요"));
	}

	public String vertexLocation() {
		return read(KEY_VERTEX_LOCATION).orElse(DEFAULT_VERTEX_LOCATION);
	}

	public String vertexGcsBucket() {
		return read(KEY_VERTEX_GCS_BUCKET).orElseThrow(() -> new IllegalStateException(
				KEY_VERTEX_GCS_BUCKET + " 미설정 — app_setting에 배치용 GCS 버킷 등록 필요"));
	}

	public int recentWindow() {
		return read(KEY_RECENT_WINDOW).map(Integer::parseInt).orElse(DEFAULT_RECENT_WINDOW);
	}
```

- [ ] **Step 3: 컴파일 확인**

Run: `cd .worktrees/vertex && ./gradlew :analytics:compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add analytics/build.gradle analytics/src/main/java/com/celfit/analytics/config/AnalyticsSettings.java
git commit -m "feat(analytics): Vertex 설정 키·인증 라이브러리 추가"
```

---

### Task 2: VertexTokenProvider

**Files:**
- Create: `analytics/src/main/java/com/celfit/analytics/llm/VertexTokenProvider.java`

서비스 계정 JSON → OAuth 토큰. 실키 없이는 단위 테스트 불가한 얇은 래퍼라 테스트는 스모크(런북)에서. `Supplier<String>`로 노출해 `VertexHttpApi` 테스트는 fake 토큰을 주입한다.

- [ ] **Step 1: 클래스 작성**

```java
package com.celfit.analytics.llm;

import com.google.auth.oauth2.GoogleCredentials;
import java.io.FileInputStream;
import java.util.List;
import java.util.function.Supplier;

/**
 * Vertex AI 인증 — 서비스 계정 JSON(GOOGLE_APPLICATION_CREDENTIALS 경로) → cloud-platform
 * 스코프 액세스 토큰. 만료 자동 갱신(라이브러리가 5분 여유로 판단). 실키 필요라 검증은 스모크.
 */
public final class VertexTokenProvider implements Supplier<String> {

	private static final String SCOPE = "https://www.googleapis.com/auth/cloud-platform";

	private final GoogleCredentials credentials;

	VertexTokenProvider(GoogleCredentials credentials) {
		this.credentials = credentials;
	}

	public static VertexTokenProvider fromEnv() {
		String path = System.getenv("GOOGLE_APPLICATION_CREDENTIALS");
		if (path == null || path.isBlank()) {
			throw new IllegalStateException(
					"GOOGLE_APPLICATION_CREDENTIALS 미설정 — SA 키 경로 셸 export 필요 (.env는 JVM에 자동 로드되지 않음)");
		}
		try (FileInputStream in = new FileInputStream(path)) {
			return new VertexTokenProvider(GoogleCredentials.fromStream(in).createScoped(List.of(SCOPE)));
		} catch (java.io.IOException e) {
			throw new IllegalStateException("SA 키 로드 실패: " + path, e);
		}
	}

	@Override
	public synchronized String get() {
		try {
			credentials.refreshIfExpired();
		} catch (java.io.IOException e) {
			throw new IllegalStateException("Vertex 액세스 토큰 갱신 실패", e);
		}
		return credentials.getAccessToken().getTokenValue();
	}
}
```

- [ ] **Step 2: 컴파일 확인**

Run: `./gradlew :analytics:compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add analytics/src/main/java/com/celfit/analytics/llm/VertexTokenProvider.java
git commit -m "feat(analytics): 서비스 계정 토큰 프로바이더 추가"
```

---

### Task 3: VertexHttpApi — 동기 경로 (TDD)

**Files:**
- Create: `analytics/src/test/java/com/celfit/analytics/llm/VertexHttpApiTest.java`
- Create: `analytics/src/main/java/com/celfit/analytics/llm/VertexHttpApi.java`

`GeminiHttpApiTest`의 로컬 `HttpServer` 패턴을 그대로 따른다. 요청 바디는 `GeminiHttpApi.requestBody()` 재사용(같은 패키지 static). RPM 페이싱 없음(DSQ), 429/5xx 백오프 유지.

- [ ] **Step 1: 실패하는 테스트 작성**

```java
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
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :analytics:test --tests VertexHttpApiTest`
Expected: 컴파일 실패 (VertexHttpApi 없음)

- [ ] **Step 3: 최소 구현 (동기 부분 — 배치 메서드는 Task 4에서 채움)**

```java
package com.celfit.analytics.llm;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Vertex AI REST 구현 — GeminiHttpApi와 동일 바디(requestBody 재사용), 차이는
 * ①호스트/경로(projects/{p}/locations/{loc}/publishers/google/models/{m}) ②Bearer 토큰
 * ③RPM 페이싱 없음(DSQ — 고정 한도가 없어 페이싱 무의미, 429는 일시 용량 부족).
 * 재시도 소진 429의 LlmQuotaExhaustedException은 잡 이월 로직 호환을 위해 유지
 * (의미: 일 한도 → 일시 용량 부족 이월).
 */
public final class VertexHttpApi implements GeminiApi, GeminiBatchApi {

	private static final Logger log = LoggerFactory.getLogger(VertexHttpApi.class);
	private static final String DEFAULT_BASE_URL = "https://aiplatform.googleapis.com";
	private static final String DEFAULT_STORAGE_URL = "https://storage.googleapis.com";
	private static final int MAX_ATTEMPTS = 6;

	private final Supplier<String> token;
	private final String baseUrl;
	private final String storageUrl;
	private final String project;
	private final String location;
	private final String bucket;
	private final long retryBaseMillis;
	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
	private final ObjectMapper om = new ObjectMapper();

	public VertexHttpApi(Supplier<String> token, String baseUrl, String storageUrl,
			String project, String location, String bucket, long retryBaseMillis) {
		this.token = token;
		this.baseUrl = baseUrl;
		this.storageUrl = storageUrl;
		this.project = project;
		this.location = location;
		this.bucket = bucket;
		this.retryBaseMillis = retryBaseMillis;
	}

	/** 운영 생성자 — SA 키는 GOOGLE_APPLICATION_CREDENTIALS, 프로젝트·버킷은 app_setting. */
	public static VertexHttpApi fromEnv(com.celfit.analytics.config.AnalyticsSettings settings) {
		return new VertexHttpApi(VertexTokenProvider.fromEnv(), DEFAULT_BASE_URL, DEFAULT_STORAGE_URL,
				settings.vertexProject(), settings.vertexLocation(), settings.vertexGcsBucket(), 15_000);
	}

	private String modelPath(String model) {
		return "projects/" + project + "/locations/" + location + "/publishers/google/models/" + model;
	}

	@Override
	public String generateJson(String model, String systemInstruction, String userText,
			InlineImage image, String schemaJson, int maxOutputTokens) {
		String body = GeminiHttpApi.requestBody(om, systemInstruction, userText, image,
				schemaJson, maxOutputTokens);
		String responseBody = send("/v1/" + modelPath(model) + ":generateContent", body);
		JsonNode root = om.readTree(responseBody);
		JsonNode usage = root.path("usageMetadata");
		// 어드민 비용 카드가 이 로그 형태를 소비 — GeminiHttpApi와 동일 포맷 유지
		log.info("gemini usage: model={} input={} output={}", model,
				usage.path("promptTokenCount").asInt(), usage.path("candidatesTokenCount").asInt());
		JsonNode text = root.path("candidates").path(0).path("content").path("parts").path(0).path("text");
		if (text.isMissingNode()) {
			throw new IllegalStateException("Vertex 응답에 본문 없음: " + abbreviate(responseBody));
		}
		return text.asString();
	}

	/** POST 공통 — 페이싱 없음, 429/5xx 지수 백오프만. */
	String send(String path, String jsonBody) {
		for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
			int status;
			String responseBody;
			try {
				HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + path))
						.timeout(Duration.ofSeconds(120))
						.header("Content-Type", "application/json")
						.header("Authorization", "Bearer " + token.get())
						.POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
						.build();
				HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
				status = res.statusCode();
				responseBody = res.body();
			} catch (java.io.IOException | InterruptedException e) {
				throw new IllegalStateException("Vertex 호출 실패: " + path, e);
			}
			if (status >= 200 && status < 300) {
				return responseBody;
			}
			boolean retryable = status == 429 || status == 500 || status == 503;
			if (retryable && attempt < MAX_ATTEMPTS) {
				long wait = retryBaseMillis * attempt;
				log.warn("vertex HTTP {} — {}ms 후 재시도 ({}/{})", status, wait, attempt, MAX_ATTEMPTS);
				sleep(wait);
				continue;
			}
			if (status == 429) {
				throw new LlmQuotaExhaustedException("Vertex 429 재시도 소진 — 일시 용량 부족, 잔여 이월");
			}
			throw new IllegalStateException("Vertex HTTP " + status + ": " + abbreviate(responseBody));
		}
		throw new IllegalStateException("도달 불가");
	}

	// --- GeminiBatchApi: Task 4에서 구현 (여기서는 컴파일용 스텁 금지 — Task 4와 같은 커밋이 아니면
	//     UnsupportedOperationException 던지는 임시 구현으로 두되 Task 4에서 반드시 대체) ---
	@Override
	public String uploadFile(byte[] jsonl, String displayName) {
		throw new UnsupportedOperationException("Task 4에서 구현");
	}

	@Override
	public String createBatch(String model, String inputFileName, String displayName) {
		throw new UnsupportedOperationException("Task 4에서 구현");
	}

	@Override
	public String getBatch(String batchName) {
		throw new UnsupportedOperationException("Task 4에서 구현");
	}

	@Override
	public String downloadFile(String fileName) {
		throw new UnsupportedOperationException("Task 4에서 구현");
	}

	private static void sleep(long millis) {
		try {
			Thread.sleep(millis);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("대기 중 인터럽트", e);
		}
	}

	private static String abbreviate(String s) {
		return s == null ? "(없음)" : s.length() > 300 ? s.substring(0, 300) + "…" : s;
	}
}
```

주의: `GeminiHttpApi.requestBody`는 package-private static — 접근 가능 확인(같은 패키지). 아니라면 가시성만 패키지로 조정.

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :analytics:test --tests VertexHttpApiTest`
Expected: PASS (5건)

- [ ] **Step 5: Commit**

```bash
git add analytics/src/main/java/com/celfit/analytics/llm/VertexHttpApi.java \
        analytics/src/test/java/com/celfit/analytics/llm/VertexHttpApiTest.java
git commit -m "feat(analytics): VertexHttpApi 동기 경로 — global 엔드포인트·Bearer·페이싱 제거"
```

---

### Task 4: VertexHttpApi — 배치 + GCS (TDD)

**Files:**
- Modify: `analytics/src/test/java/com/celfit/analytics/llm/VertexHttpApiTest.java`
- Modify: `analytics/src/main/java/com/celfit/analytics/llm/VertexHttpApi.java`

`GeminiBatchApi` 4메서드의 Vertex 의미: `uploadFile` → GCS `input/{displayName}.jsonl` 업로드 후 `gs://` URI 반환. `createBatch` → `batchPredictionJobs` 생성(출력 prefix `gs://{bucket}/output/{displayName}/`), 잡 리소스 이름 반환. `getBatch` → 잡 JSON. `downloadFile(prefix)` → prefix 밑 `.jsonl` 오브젝트 전부 목록 조회 후 내용 병합. 인터페이스 시그니처는 무변경(의미만 구현별).

- [ ] **Step 1: 실패하는 테스트 추가** (VertexHttpApiTest에 append)

```java
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
		// OK_RESPONSE 대신 잡 응답이 필요 — 서버 핸들러가 경로에 batchPredictionJobs면 잡 JSON 반환하도록
		// setUp 핸들러를 수정한다 (아래 Step 1b 참조)
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
	void 결과_다운로드는_prefix_목록의_jsonl을_병합한다() {
		// 목록 API가 predictions 파일 2개를 돌려주고, 각각 내용이 한 줄씩이라고 가정
		String merged = api().downloadFile("gs://test-bucket/output/backfill-1/job-123");
		assertEquals("{\"line\":1}\n{\"line\":2}\n", merged);
	}
```

**Step 1b: setUp 핸들러를 경로 분기형으로 교체** (기존 단일 핸들러 대체):

```java
		server.createContext("/", ex -> {
			ByteArrayOutputStream buf = new ByteArrayOutputStream();
			ex.getRequestBody().transferTo(buf);
			String uri = ex.getRequestURI().toString();
			bodies.add(buf.toString(StandardCharsets.UTF_8));
			paths.add(uri);
			authHeaders.add(ex.getRequestHeaders().getFirst("Authorization"));
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
			} else if (uri.startsWith("/storage/") && uri.contains("prefix=")) {
				response = "{\"items\":[{\"name\":\"output/backfill-1/job-123/predictions_1.jsonl\"},"
						+ "{\"name\":\"output/backfill-1/job-123/predictions_2.jsonl\"}]}";
			} else if (uri.contains("predictions_1")) {
				response = "{\"line\":1}\n";
			} else if (uri.contains("predictions_2")) {
				response = "{\"line\":2}\n";
			} else {
				response = OK_RESPONSE;
			}
			byte[] out = response.getBytes(StandardCharsets.UTF_8);
			ex.sendResponseHeaders(code, out.length);
			ex.getResponseBody().write(out);
			ex.close();
		});
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :analytics:test --tests VertexHttpApiTest`
Expected: 신규 3건 FAIL (UnsupportedOperationException), 기존 5건 PASS

- [ ] **Step 3: 배치 메서드 구현** (스텁 4개 교체)

```java
	/** GCS media 업로드(입력 ~50MB — resumable 불필요) → gs:// URI 반환. */
	@Override
	public String uploadFile(byte[] jsonl, String displayName) {
		String object = "input/" + displayName + ".jsonl";
		String encoded = java.net.URLEncoder.encode(object, StandardCharsets.UTF_8);
		try {
			HttpRequest req = HttpRequest.newBuilder(URI.create(storageUrl
					+ "/upload/storage/v1/b/" + bucket + "/o?uploadType=media&name=" + encoded))
					.timeout(Duration.ofMinutes(5))
					.header("Content-Type", "application/jsonl")
					.header("Authorization", "Bearer " + token.get())
					.POST(HttpRequest.BodyPublishers.ofByteArray(jsonl))
					.build();
			HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
			if (res.statusCode() < 200 || res.statusCode() >= 300) {
				throw new IllegalStateException("GCS 업로드 실패 HTTP " + res.statusCode()
						+ ": " + abbreviate(res.body()));
			}
			return "gs://" + bucket + "/" + object;
		} catch (java.io.IOException | InterruptedException e) {
			throw new IllegalStateException("GCS 업로드 실패: " + object, e);
		}
	}

	/**
	 * 배치 잡 생성 — 모델은 짧은 퍼블리셔 경로(publishers/google/models/{id}) 사용.
	 * 공식 레퍼런스 예시 형식(2026-07 조사) — 스모크에서 거부되면 modelPath(model) 풀 경로로 교체.
	 */
	@Override
	public String createBatch(String model, String inputFileName, String displayName) {
		tools.jackson.databind.node.ObjectNode body = om.createObjectNode();
		body.put("displayName", displayName);
		body.put("model", "publishers/google/models/" + model);
		tools.jackson.databind.node.ObjectNode input = body.putObject("inputConfig");
		input.put("instancesFormat", "jsonl");
		input.putObject("gcsSource").putArray("uris").add(inputFileName);
		tools.jackson.databind.node.ObjectNode output = body.putObject("outputConfig");
		output.put("predictionsFormat", "jsonl");
		output.putObject("gcsDestination").put("outputUriPrefix",
				"gs://" + bucket + "/output/" + displayName + "/");
		String res = send("/v1/projects/" + project + "/locations/" + location
				+ "/batchPredictionJobs", om.writeValueAsString(body));
		return om.readTree(res).path("name").asString();
	}

	/** 잡 조회 — 상태(state=JOB_STATE_*)·outputInfo.gcsOutputDirectory 탐색은 호출자(러너). */
	@Override
	public String getBatch(String batchName) {
		return get(baseUrl + "/v1/" + batchName, "배치 조회");
	}

	/** gs:// prefix 밑 .jsonl 오브젝트 목록 조회 후 내용 병합(파일별 개행 보장). */
	@Override
	public String downloadFile(String fileName) {
		if (!fileName.startsWith("gs://" + bucket + "/")) {
			throw new IllegalStateException("예상 밖 출력 위치(버킷 불일치): " + fileName);
		}
		String prefix = fileName.substring(("gs://" + bucket + "/").length());
		String listUrl = storageUrl + "/storage/v1/b/" + bucket + "/o?prefix="
				+ java.net.URLEncoder.encode(prefix, StandardCharsets.UTF_8);
		JsonNode items = om.readTree(get(listUrl, "출력 목록 조회")).path("items");
		StringBuilder merged = new StringBuilder();
		for (JsonNode item : items) {
			String name = item.path("name").asString();
			if (!name.endsWith(".jsonl")) {
				continue;
			}
			String objUrl = storageUrl + "/storage/v1/b/" + bucket + "/o/"
					+ java.net.URLEncoder.encode(name, StandardCharsets.UTF_8) + "?alt=media";
			String content = get(objUrl, "결과 다운로드");
			merged.append(content);
			if (!content.endsWith("\n")) {
				merged.append('\n');
			}
		}
		return merged.toString();
	}

	private String get(String url, String what) {
		try {
			HttpRequest req = HttpRequest.newBuilder(URI.create(url))
					.timeout(Duration.ofMinutes(5))
					.header("Authorization", "Bearer " + token.get()).GET().build();
			HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
			if (res.statusCode() < 200 || res.statusCode() >= 300) {
				throw new IllegalStateException(what + " 실패 HTTP " + res.statusCode());
			}
			return res.body();
		} catch (java.io.IOException | InterruptedException e) {
			throw new IllegalStateException(what + " 실패", e);
		}
	}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :analytics:test --tests VertexHttpApiTest`
Expected: PASS (8건)

- [ ] **Step 5: Commit**

```bash
git add analytics/src/main/java/com/celfit/analytics/llm/VertexHttpApi.java \
        analytics/src/test/java/com/celfit/analytics/llm/VertexHttpApiTest.java
git commit -m "feat(analytics): VertexHttpApi 배치 경로 — GCS 입출력·batchPredictionJobs"
```

---

### Task 5: 백필 러너 — Vertex 출력 형식 대응 (TDD)

**Files:**
- Modify: `analytics/src/test/java/com/celfit/analytics/analyze/GeminiBackfillRunnerTest.java`
- Modify: `analytics/src/main/java/com/celfit/analytics/analyze/GeminiBackfillRunner.java`

세 가지 변경: ①`collect`의 결과 파일 탐색 후보에 Vertex `outputInfo.gcsOutputDirectory` 추가 ②결과 라인의 short_code를 `key` 부재 시 request 에코 첫 줄(`콘텐츠: {shortCode} (`)에서 복원 ③Vertex 실패 라인(`status` 비어있지 않음) 실패 카운트. `requestLine`은 camelCase로 통일(proto JSON 파서는 양쪽 다 수용 — AI Studio 동기 경로가 camelCase로 이미 검증됨).

- [ ] **Step 1: 기존 테스트 구조 파악 후 실패하는 테스트 추가**

`GeminiBackfillRunnerTest`의 기존 fake `GeminiBatchApi`·컬렉트 픽스처 관용구를 따라 추가한다 (기존 테스트의 셋업 헬퍼를 재사용 — 아래는 검증 의도이며 헬퍼 이름은 기존 파일에 맞춘다):

```java
	@Test
	void Vertex_형식_결과라인은_에코_첫줄에서_short_code를_복원한다() {
		// key 없음 + request 에코 + response — Vertex 출력 형식
		String vertexLine = """
				{"status":"","request":{"contents":[{"role":"user","parts":[{"text":"콘텐츠: ABC123 (@handle, REELS)\\n캡션: ..."}]}]},
				 "response":{"candidates":[{"content":{"parts":[{"text":"%s"}]}}]}}"""
				.formatted(escapedInsightJson()); // 기존 테스트의 유효 인사이트 JSON 헬퍼 재사용
		// fake api가 이 라인을 돌려주도록 셋업 → collect 실행 → ABC123 저장 검증
	}

	@Test
	void Vertex_실패라인은_status가_있으면_실패로_센다() {
		String failedLine = """
				{"status":"INTERNAL","request":{"contents":[{"role":"user","parts":[{"text":"콘텐츠: XYZ (@h, FEED)"}]}]},"response":{}}""";
		// collect 후 저장 0건·실패 1건 검증
	}

	@Test
	void 잡_응답의_outputInfo_gcsOutputDirectory를_결과_위치로_쓴다() {
		String vertexJob = """
				{"name":"projects/p/locations/global/batchPredictionJobs/1","state":"JOB_STATE_SUCCEEDED",
				 "outputInfo":{"gcsOutputDirectory":"gs://b/output/backfill/job-1"}}""";
		// getBatch가 위를 돌려줄 때 downloadFile("gs://b/output/backfill/job-1")이 호출되는지 검증
	}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :analytics:test --tests GeminiBackfillRunnerTest`
Expected: 신규 3건 FAIL

- [ ] **Step 3: 구현**

`collect()`의 `resultFile` 후보에 추가 (기존 firstNonNull 마지막 인자로):

```java
		String resultFile = firstNonNull(
				text(batch, "metadata", "output", "responsesFile"),
				text(batch, "response", "responsesFile"),
				text(batch, "dest", "fileName"),
				text(batch, "metadata", "dest", "fileName"),
				text(batch, "outputInfo", "gcsOutputDirectory"));
```

결과 라인 루프에서 short_code 결정·실패 판정 교체 (기존 `String shortCode = node.path("key")...` 부분):

```java
				JsonNode node = om.readTree(line);
				String vertexStatus = node.path("status").asString("");
				if (!vertexStatus.isEmpty()) {
					failed++;
					log.warn("배치 실패 라인 (status={}): {}", vertexStatus, abbreviate(line));
					continue;
				}
				String shortCode = node.path("key").asString("");
				if (shortCode.isEmpty()) {
					shortCode = shortCodeFromEcho(node);
				}
```

에코 복원 헬퍼 추가:

```java
	private static final java.util.regex.Pattern ECHO_SHORT_CODE =
			java.util.regex.Pattern.compile("^콘텐츠: (\\S+) \\(");

	/** Vertex 출력엔 key가 없다 — 에코된 request의 유저 텍스트 첫 줄(콘텐츠: {shortCode} ()에서 복원. */
	static String shortCodeFromEcho(JsonNode node) {
		JsonNode parts = node.path("request").path("contents").path(0).path("parts");
		for (JsonNode part : parts) {
			String text = part.path("text").asString("");
			java.util.regex.Matcher m = ECHO_SHORT_CODE.matcher(text);
			if (m.find()) {
				return m.group(1);
			}
		}
		return "";
	}
```

`requestLine`의 snake_case 키를 camelCase로 교체 (`system_instruction`→`systemInstruction`, `generation_config`→`generationConfig`, `response_mime_type`→`responseMimeType`, `response_schema`→`responseSchema`, `max_output_tokens`→`maxOutputTokens`) — proto JSON은 양쪽 수용이라 AI Studio 경로에도 무해. 기존 requestLine 단위 테스트가 snake_case를 단언하면 camelCase로 갱신.

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :analytics:test --tests GeminiBackfillRunnerTest`
Expected: PASS (기존+신규 전체)

- [ ] **Step 5: Commit**

```bash
git add analytics/src/main/java/com/celfit/analytics/analyze/GeminiBackfillRunner.java \
        analytics/src/test/java/com/celfit/analytics/analyze/GeminiBackfillRunnerTest.java
git commit -m "feat(analytics): 백필 러너 Vertex 출력 대응 — 에코 복원·실패 라인·출력 디렉토리"
```

---

### Task 6: 배선 — LlmConfig·JobConfig

**Files:**
- Modify: `analytics/src/main/java/com/celfit/analytics/llm/LlmConfig.java`
- Modify: `analytics/src/main/java/com/celfit/analytics/config/JobConfig.java`

- [ ] **Step 1: LlmConfig의 geminiApi 빈을 프로바이더 분기로 교체**

```java
	@Bean
	@Lazy
	public GeminiApi geminiApi(AnalyticsSettings settings) {
		if ("vertex".equals(settings.llmProvider())) {
			return VertexHttpApi.fromEnv(settings); // SA 토큰 + app_setting 프로젝트/버킷
		}
		return GeminiHttpApi.fromEnv(settings.geminiRpm()); // GEMINI_API_KEY (무료 프로젝트)
	}
```

클래스 javadoc의 프로바이더 서술에 `vertex` 값 추가: `gemini(기본) | vertex(Vertex AI — 07-20 전환) | anthropic(롤백)`. `contentInsightPort`/`accountSynthesisPort`의 분기는 무변경 — vertex는 "anthropic이 아님" 경로로 기존 Gemini 어댑터를 그대로 타며, 주입되는 `GeminiApi` 빈만 Vertex 구현이 된다.

- [ ] **Step 2: JobConfig의 백필 러너를 프로바이더 분기로 교체**

`geminiBackfillRunner` 빈에서 `GeminiHttpApi.fromEnvPaid()` 부분을:

```java
			com.celfit.analytics.llm.GeminiBatchApi batchApi =
					"vertex".equals(settings.llmProvider())
							? com.celfit.analytics.llm.VertexHttpApi.fromEnv(settings)
							: com.celfit.analytics.llm.GeminiHttpApi.fromEnvPaid();
			GeminiBackfillRunner runner = new GeminiBackfillRunner(rawJdbcTemplate, analysisDataSource,
					batchApi, settings,
					new com.celfit.analytics.llm.BeautyTaxonomyLoader(analysisDataSource),
					java.nio.file.Path.of(dir));
```

주석의 "유료 키 Batch" 서술에 "provider=vertex면 Vertex 배치(GCS)" 추가.

- [ ] **Step 3: 전체 테스트**

Run: `./gradlew :analytics:test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add analytics/src/main/java/com/celfit/analytics/llm/LlmConfig.java \
        analytics/src/main/java/com/celfit/analytics/config/JobConfig.java
git commit -m "feat(analytics): llm-provider=vertex 배선 — 동기·백필 배치 모두 Vertex 선택 가능"
```

---

### Task 7: 04 뷰 — 최근 N 윈도우 OR 자격 + timely 컬럼

**Files:**
- Modify: `analytics/views/04_analysis_candidates.sql`
- Modify: `analytics/test/04_analysis_candidates.test.sql`

⚠️ 순서 중요: 뷰 수정 전에 기존 하니스가 그린인지 먼저 확인한다. 이번 변경으로 **기존 기대치가 의도적으로 뒤집힌다** — 제때 크롤 실패라도 최근 N 윈도우 안이면 이제 후보다(timely=false). 기존 ASSERT 중 "r1·r2 제외" 류는 시드가 최근 윈도우 안인지에 따라 갱신 대상.

- [ ] **Step 1: 기존 하니스 그린 베이스라인 확인**

Run: `cd analytics/test && ./run.sh` (DB 컨테이너 기동 필요 — `docker start crawler-postgres-1`, 이름 다르면 `PG_CONTAINER`로 오버라이드)
Expected: 전체 PASS. 실패 시 여기서 멈추고 원인 파악(우리 변경과 무관한 깨짐을 섞지 않는다).

- [ ] **Step 2: 뷰 교체**

`04_analysis_candidates.sql` 전체를 다음으로 교체 (변경점: LATERAL로 timely 계산 승격, WHERE의 EXISTS를 `(timely OR 최근 N 윈도우)` 로, 헤더 주석 갱신):

```sql
-- LLM 캡션 선분석 후보 (분석 잡 전용 — 미러 안 함). 스펙 2026-07-17 §5, 07-20 백필 재도입 개정.
-- raw만 보고 판단 가능한 자격까지만 뷰가 담당: 뷰티 모수 ∩ ENUMERATION ∩ 캡션 존재 ∩
-- 숙성(uploaded_at + 'analytics.analyze-maturity-days'(기본 3)일 경과) ∩
-- (제때 크롤 OR 최근 N개 윈도우 포함).
-- '이미 분석됨' 제외·배치 상한·정렬 정책은 Java 몫.
--
-- 자격 개정(07-20, PO 결정 — 스펙 docs/superpowers/specs/2026-07-20-vertex-migration-recent12-backfill-design.md):
-- "백필 MVP 제외"(07-19)를 번복, 최근 N개(app_setting 'analytics.recent-window', 01 뷰와 공유) 윈도우
-- 안이면 제때 크롤 실패(늦크롤 백필)여도 후보에 포함한다. timely 컬럼(제때 크롤 여부)을 노출해
-- 소비자(백필 러너·일상 잡)가 V33 metric_timeliness 마킹(timely/late_backfill)을 결정한다.
-- 제때 크롤 판정(EXISTS — 07-19 재재정정 유지): 원본 스냅샷 중 [posted+pin, posted+pin+slack)에
-- usable 스냅샷 존재. usable = 지표 완비(릴스는 views·likes·comments, 피드는 likes·comments).
CREATE OR REPLACE VIEW analytics.v_analysis_candidates AS
SELECT
  v.short_code,
  v.content_type,
  v.account_handle,
  v.posted_at AS uploaded_at,
  v.caption,
  v.thumbnail_url,
  pr.followers,
  v.views,
  v.likes,
  v.comments,
  v.metric_captured_at,
  t.timely
FROM analytics.v_contents v
LEFT JOIN analytics.v_base_profile pr ON pr.username = v.account_handle
CROSS JOIN LATERAL (
  SELECT EXISTS (
    -- 창 안에 usable 스냅샷이 실제로 존재하는가 (핀 무관·결정론 — 07-19 재재정정)
    SELECT 1
    FROM analytics.v_serving_content sc
    JOIN analytics.v_base_content_snapshot s USING (content_id)
    WHERE sc.short_code = v.short_code
      AND s.captured_at >= v.posted_at + make_interval(days => COALESCE(
            (SELECT value::int FROM app_setting WHERE key = 'analytics.metric-pin-days'), 3))
      AND s.captured_at <  v.posted_at + make_interval(days => COALESCE(
            (SELECT value::int FROM app_setting WHERE key = 'analytics.metric-pin-days'), 3)
          + COALESCE(
            (SELECT value::int FROM app_setting WHERE key = 'analytics.analyze-timely-slack-days'), 2))
      AND s.likes IS NOT NULL AND s.comments_count IS NOT NULL
      AND (sc.content_type <> 'REELS' OR s.views IS NOT NULL)
  ) AS timely
) t
WHERE v.caption IS NOT NULL AND btrim(v.caption) <> ''
  AND v.posted_at + make_interval(days => COALESCE(
        (SELECT value::int FROM app_setting WHERE key = 'analytics.analyze-maturity-days'), 3)) <= now()
  AND (
    t.timely
    -- 늦크롤 백필: 최근 N개 윈도우(01 뷰) 안이면 포함 — 지표는 핀(v_contents) 그대로, 마킹은 소비자가
    OR EXISTS (SELECT 1 FROM analytics.v_recent_content rw WHERE rw.short_code = v.short_code)
  );
```

주의: `CREATE OR REPLACE VIEW`는 기존 컬럼 순서 유지 + 끝에만 추가 가능 — `timely`는 마지막 컬럼. 만약 적용 시 컬럼 충돌 에러가 나면 `DROP VIEW` 후 재생성이 아니라 원인을 확인할 것(다른 뷰가 04에 의존하는지 `\d+` 확인 — 현재는 의존 뷰 없음이 전제).

- [ ] **Step 3: 하니스 기대치 갱신 + 신규 케이스**

`04_analysis_candidates.test.sql`에서:
1. 실행해 보고(아래 Step 4) 뒤집힌 ASSERT를 식별한다. 예상: `dummy_r1`·`dummy_r2`(제때 크롤 실패)가 최근 윈도우 안 시드라면 이제 **포함**(timely=false)으로 바뀜. 각 ASSERT를 새 의미로 고치고 주석에 "07-20 백필 재도입: 창 안이면 늦크롤도 후보(timely=false)" 근거를 단다.
2. 신규 검증 블록 추가 (파일 끝):

```sql
-- 07-20 백필 재도입: timely 컬럼과 OR 자격 검증.
DO $$
BEGIN
  -- 제때 크롤 성공분은 timely=true
  ASSERT (SELECT timely FROM analytics.v_analysis_candidates WHERE short_code = 'dummy_f1'),
    'f1(제때 크롤)의 timely가 true가 아님';
  -- 늦크롤이지만 최근 윈도우 안 → 포함 + timely=false (r1은 창 안 usable 스냅 없음)
  ASSERT EXISTS (SELECT 1 FROM analytics.v_analysis_candidates
                 WHERE short_code = 'dummy_r1' AND NOT timely),
    'r1(늦크롤·윈도우 안)이 timely=false 후보로 포함되지 않음';
END $$;

-- 윈도우 밖 늦크롤은 여전히 제외: recent-window를 1로 좁혀 r1을 창 밖으로 밀어낸다.
INSERT INTO app_setting(key, value) VALUES ('analytics.recent-window', '1')
  ON CONFLICT (key) DO UPDATE SET value = excluded.value;
DO $$
BEGIN
  ASSERT NOT EXISTS (SELECT 1 FROM analytics.v_analysis_candidates
                     WHERE short_code = 'dummy_r1' AND NOT timely),
    'recent-window=1인데 늦크롤 r1이 여전히 후보에 있음 (창 밖 제외 실패)';
END $$;
DELETE FROM app_setting WHERE key = 'analytics.recent-window';
```

(시드 계정의 콘텐츠 개수·최신순은 기존 픽스처를 확인해 short_code 선택을 맞춘다 — r1이 최신이면 창=1에서도 남으므로 그 경우 더 최신 시드를 창 대표로 두고 r1이 밀리는 값으로 조정.)

- [ ] **Step 4: 하니스 실행·수렴**

Run: `cd analytics/test && ./run.sh`
Expected: 전체 PASS (04 포함). 04 이후 번호(10·20)의 간접 깨짐 없음 확인.

- [ ] **Step 5: Commit**

```bash
git add analytics/views/04_analysis_candidates.sql analytics/test/04_analysis_candidates.test.sql
git commit -m "feat(analytics): 분석 자격에 최근 N 윈도우 OR 추가 — 백필 재도입, timely 노출"
```

---

### Task 8: ContentAnalysisJob — 자격 OR + 마킹 분기 (TDD)

**Files:**
- Modify: `analytics/src/test/java/com/celfit/analytics/analyze/ContentAnalysisJobTest.java`
- Modify: `analytics/src/main/java/com/celfit/analytics/analyze/ContentAnalysisJob.java`

- [ ] **Step 1: 실패하는 테스트 추가**

기존 `ContentAnalysisJobTest`의 픽스처 관용구(분석 DB 시드·fake insight)를 따라:

```java
	@Test
	void 늦크롤이라도_최근_윈도우_안이면_분석하고_late_backfill로_마킹한다() {
		// 시드: metric_captured_at이 posted+pin+slack 이후(늦크롤), 계정의 최근 12개 안
		// run() 후 content_analyses에 저장되고 metric_timeliness='late_backfill' 검증
	}

	@Test
	void 제때_크롤분은_timely로_마킹한다() {
		// 기존 경로 회귀: metric_captured_at이 창 안 → metric_timeliness='timely'
	}

	@Test
	void 늦크롤이면서_윈도우_밖이면_여전히_제외된다() {
		// 시드: 늦크롤 + 계정 게시물 13개 중 recency 13위 → 대상 아님
	}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :analytics:test --tests ContentAnalysisJobTest`
Expected: 신규 FAIL

- [ ] **Step 3: 구현**

`run()`의 `Set<String> eligible` 블록을 `Map<String, Boolean>`(short_code → timely)로 교체:

```java
		// 자격(07-20 개정 — 스펙 2026-07-20): 제때 크롤 OR 최근 N개 윈도우(계정별 recency).
		// timely 여부를 함께 읽어 V33 마킹(timely/late_backfill)을 행 단위로 결정한다.
		// 미러 contents에는 raw content_id가 없어 recency 동률 타이브레이크는 short_code DESC —
		// 01 뷰(content_id DESC)와 미세하게 다를 수 있으나 같은 초 게시물 동률에서만 갈리는 수준.
		int pinDays = settings.metricPinDays();
		int slackDays = settings.analyzeTimelySlackDays();
		Map<String, Boolean> eligible = new HashMap<>();
		analysis.query("""
				SELECT c.short_code,
				       COALESCE(c.metric_captured_at >= c.posted_at + make_interval(days => ?)
				                AND c.metric_captured_at < c.posted_at + make_interval(days => ?), false) AS timely
				FROM contents c
				WHERE NOT EXISTS (SELECT 1 FROM content_analyses a WHERE a.short_code = c.short_code)
				  AND (NOT EXISTS (SELECT 1 FROM content_comments m WHERE m.short_code = c.short_code)
				       OR EXISTS (SELECT 1 FROM comment_classifications k WHERE k.short_code = c.short_code))
				  AND c.posted_at <= now() - make_interval(days => ?)
				  AND ((c.metric_captured_at >= c.posted_at + make_interval(days => ?)
				        AND c.metric_captured_at < c.posted_at + make_interval(days => ?))
				       OR c.short_code IN (
				         SELECT short_code FROM (
				           SELECT short_code, row_number() OVER (
				             PARTITION BY account_handle ORDER BY posted_at DESC, short_code DESC) AS rn
				           FROM contents) w
				         WHERE w.rn <= ?))""",
				rs -> {
					eligible.put(rs.getString(1), rs.getBoolean(2));
				},
				pinDays, pinDays + slackDays, settings.analyzeMaturityDays(),
				pinDays, pinDays + slackDays, settings.recentWindow());
		List<String> targets = withBaseline.keySet().stream()
				.filter(eligible::containsKey)
				.limit(settings.analyzeBatchLimit())
				.toList();
```

(`import java.util.HashMap;` 추가. 기존 주석의 "제때 크롤 가드…백필 차단" 서술은 개정 내용으로 갱신.)

`analyzeOne` 시그니처에 timely 전달·마킹 분기 (호출부 `analyzeOne(shortCode, model, withBaseline.get(shortCode), eligible.get(shortCode))`):

```java
	private void analyzeOne(String shortCode, String model, Baseline b, boolean timely) {
```

마지막 insert 줄을:

```java
		// 자격 개정(07-20): 창 안 늦크롤도 유입 — 마킹은 제때 여부로 분기 (V33 어휘).
		ContentAnalysisWriter.insert(analysis, json, shortCode, model, b, attrs, s, false,
				timely ? "timely" : "late_backfill");
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :analytics:test --tests ContentAnalysisJobTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add analytics/src/main/java/com/celfit/analytics/analyze/ContentAnalysisJob.java \
        analytics/src/test/java/com/celfit/analytics/analyze/ContentAnalysisJobTest.java
git commit -m "feat(analytics): 일상 분석 자격에 최근 N 윈도우 OR — 늦크롤은 late_backfill 마킹"
```

---

### Task 9: 문서 — ARCHITECTURE·런북

**Files:**
- Modify: `ARCHITECTURE.md` (§5 작업 트랙 표, §7 결정 기록)
- Create: `docs/runbooks/2026-07-20-vertex-backfill-runbook.md`

- [ ] **Step 1: ARCHITECTURE.md 갱신**

§7 결정 기록에 추가 (기존 항목 서식을 따름):

```markdown
- **2026-07-20 Vertex AI 전환 + 백필 재도입**: LLM 인프라를 AI Studio 무료 티어 →
  Vertex AI(SA OAuth, global, DSQ)로 완전 전환(`analytics.llm-provider=vertex`) — $300 크레딧은
  AI Studio에 적용 불가(2026-03 정책)라 Vertex가 유일 경로. crawler 뷰티 판정은 무료 키 유지.
  분석 자격에 "최근 N 윈도우 포함" OR 추가로 07-19의 "백필 MVP 제외"를 번복(늦크롤은
  late_backfill 마킹). 스펙: docs/superpowers/specs/2026-07-20-vertex-migration-recent12-backfill-design.md
```

§5 작업 트랙 표의 해당 트랙 상태 갱신(또는 행 추가 — 표 서식 준수).

- [ ] **Step 2: 런북 작성**

`docs/runbooks/2026-07-20-vertex-backfill-runbook.md` (디렉토리 없으면 생성 — 기존 런북 위치 관례가 있으면 그쪽 우선):

```markdown
# Vertex 전환·백필 실행 런북 (2026-07-20)

> 상태: 🟢 활성 · 스펙: ../superpowers/specs/2026-07-20-vertex-migration-recent12-backfill-design.md

## 1. GCP 준비 (사용자 직접 — 계정·결제는 에이전트 불가)

1. https://console.cloud.google.com — 기존 구글 계정으로 $300 무료 체험 활성화
   (⚠️ 활성화 순간부터 90일 카운트다운 — 아래 2~4와 코드 배포가 준비된 뒤에 할 것)
2. 프로젝트 생성(예: `hypenow-llm`) → API 활성화: Vertex AI API, Cloud Storage API
3. GCS 버킷 생성: 이름 예 `hypenow-llm-batch`, 리전 `us`(멀티리전 — 모델 가용 리전 정합)
4. 서비스 계정 생성(예: `analytics-llm`) → 역할: `Vertex AI User`, `Storage Object Admin`
   → JSON 키 발급·다운로드
5. 키를 오라클 서버로 업로드: `scp key.json hypenow:/opt/hypenow/secrets/vertex-sa.json`

## 2. 서버 설정

- analytics 서비스 환경에 `GOOGLE_APPLICATION_CREDENTIALS=/opt/hypenow/secrets/vertex-sa.json`
  (compose env — `.env`는 JVM 자동 로드 안 됨)
- app_setting (raw DB):
  ```sql
  INSERT INTO app_setting(key,value) VALUES
    ('analytics.vertex-project','<프로젝트ID>'),
    ('analytics.vertex-gcs-bucket','hypenow-llm-batch')
  ON CONFLICT (key) DO UPDATE SET value=excluded.value;
  -- 전환 스위치는 스모크 후:
  -- UPDATE app_setting SET value='vertex' WHERE key='analytics.llm-provider';
  ```

## 3. 뷰 적용 + 배포

기존 운영 뷰 적용·미러·배포 런북 그대로 (⚠️ origin/develop 워크트리 기준 —
세션 간 뷰 되덮기 함정): 뷰 04 수동 적용 → analytics·was 배포.

## 4. 스모크 (순서 고정)

1. **동기 1콜**: `analytics.llm-provider=vertex` 설정 후 admin UI에서 분석 잡 1건 트리거 —
   로그 `gemini usage:` 라인·content_analyses 적재 확인. 실패 시 provider를 `gemini`로 롤백.
2. **소형 배치**: `analyze-batch-limit`를 잠시 낮춰 백필 submit이 소수 건만 담게 하는 방법이
   없으므로(러너는 전량 제출), 대신 스모크용으로 후보 10건짜리 JSONL을 만들어 확인하려면
   submit 전 `backfill-input.jsonl`을 확인한다. 실전은 3단계로 바로 가되 배치 생성 직후
   콘솔에서 잡 상태·예상 건수를 확인하고 이상하면 즉시 취소(과금은 완료분만).
   ⚠️ 배치 `model` 필드가 거부되면(400) VertexHttpApi.createBatch의 짧은 경로를
   풀 경로(`projects/{p}/locations/{loc}/publishers/google/models/{m}`)로 교체 재시도.
3. **본 백필**: `--analytics.backfill-submit=true --spring.main.web-application-type=none`
   → 로그의 잡 이름으로 (완료 후, ≤24h) `--analytics.backfill-collect=projects/.../batchPredictionJobs/NNN`
4. **수거 후 스팟체크**: `SELECT metric_timeliness, count(*) FROM content_analyses GROUP BY 1;`
   — late_backfill 증가분이 제출 건수와 정합하는지.

## 5. 롤백

`UPDATE app_setting SET value='gemini' WHERE key='analytics.llm-provider';` + analytics 재기동.
백필 수거는 멱등(ON CONFLICT DO NOTHING) — 재실행 안전.
```

- [ ] **Step 3: Commit**

```bash
git add ARCHITECTURE.md docs/runbooks/2026-07-20-vertex-backfill-runbook.md
git commit -m "docs: Vertex 전환·백필 결정 기록 + 실행 런북"
```

---

### Task 10: 전체 검증

- [ ] **Step 1: 전체 테스트**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL (crawler·was 포함 — 계약 모듈 무변경이라 영향 없음 확인)

- [ ] **Step 2: SQL 하니스 재실행**

Run: `cd analytics/test && ./run.sh`
Expected: 전체 PASS

- [ ] **Step 3: 마무리**

superpowers:finishing-a-development-branch 스킬로 통합 방식 결정(PR 대상 develop).
PR 본문에 포함: 스펙 링크, 검증 유보 항목(batch model 경로·버킷 리전 — 스모크에서 확정),
운영 적용 순서는 런북 링크.
