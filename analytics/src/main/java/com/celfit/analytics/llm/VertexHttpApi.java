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

	/** POST 공통 — 페이싱 없음, 429/5xx 선형 백오프(retryBase×시도차수)만. */
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
