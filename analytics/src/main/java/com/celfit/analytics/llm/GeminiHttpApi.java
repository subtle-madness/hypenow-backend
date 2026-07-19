package com.celfit.analytics.llm;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Gemini REST 구현 — 검증된 호출 형태(골드셋 스파이크 run_gemini.py) 그대로:
 * systemInstruction + responseSchema 구조화 출력, temperature 0.
 * 무료 티어 대응: RPM 페이싱(분당 rpm콜 균등) + 429/5xx 지수 백오프, 재시도 소진 429는 일 한도로 간주.
 */
public final class GeminiHttpApi implements GeminiApi, GeminiBatchApi {

	private static final Logger log = LoggerFactory.getLogger(GeminiHttpApi.class);
	private static final String DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com";
	private static final int MAX_ATTEMPTS = 6;

	private final String apiKey;
	private final String baseUrl;
	private final long paceIntervalMillis;
	private final long retryBaseMillis;
	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
	private final ObjectMapper om = new ObjectMapper();
	private long nextAllowedAt; // 페이싱 — 다음 호출 허용 시각 (synchronized 접근)

	public GeminiHttpApi(String apiKey, String baseUrl, int rpm, long retryBaseMillis) {
		this.apiKey = apiKey;
		this.baseUrl = baseUrl;
		this.paceIntervalMillis = Math.max(0, 60_000L / Math.max(1, rpm));
		this.retryBaseMillis = retryBaseMillis;
	}

	/** 무료 프로젝트 키(GEMINI_API_KEY) — 일상 파이프라인용. */
	public static GeminiHttpApi fromEnv(int rpm) {
		return new GeminiHttpApi(requireEnv("GEMINI_API_KEY"), DEFAULT_BASE_URL, rpm, 15_000);
	}

	/** 유료 프로젝트 키(GEMINI_API_KEY_PAID) — 백필 Batch 전용. 일상 파이프라인에서 쓰지 않는다. */
	public static GeminiHttpApi fromEnvPaid() {
		return new GeminiHttpApi(requireEnv("GEMINI_API_KEY_PAID"), DEFAULT_BASE_URL, 60, 15_000);
	}

	static String requireEnv(String name) {
		String v = System.getenv(name);
		if (v == null || v.isBlank()) {
			throw new IllegalStateException(name + " 미설정 — 셸 export 필요 (.env는 JVM에 자동 로드되지 않음)");
		}
		return v;
	}

	@Override
	public String generateJson(String model, String systemInstruction, String userText,
			InlineImage image, String schemaJson, int maxOutputTokens) {
		String body = requestBody(om, systemInstruction, userText, image, schemaJson, maxOutputTokens);
		String responseBody = send("/v1beta/models/" + model + ":generateContent", body);
		JsonNode root = om.readTree(responseBody);
		JsonNode usage = root.path("usageMetadata");
		// 건당 비용·예산 소진 추적 근거 (무료 티어 일 1,500콜 예산 — 07-18 확정)
		log.info("gemini usage: model={} input={} output={}", model,
				usage.path("promptTokenCount").asInt(), usage.path("candidatesTokenCount").asInt());
		JsonNode text = root.path("candidates").path(0).path("content").path("parts").path(0).path("text");
		if (text.isMissingNode()) {
			throw new IllegalStateException("Gemini 응답에 본문 없음: " + abbreviate(responseBody));
		}
		return text.asString();
	}

	/** POST 공통 — 페이싱 + 429/5xx 백오프. 백필 러너의 배치 엔드포인트도 이 경로를 쓴다. */
	String send(String path, String jsonBody) {
		for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
			pace();
			int status;
			String responseBody;
			try {
				HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + path))
						.timeout(Duration.ofSeconds(120))
						.header("Content-Type", "application/json")
						.header("x-goog-api-key", apiKey)
						.POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
						.build();
				HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
				status = res.statusCode();
				responseBody = res.body();
			} catch (java.io.IOException | InterruptedException e) {
				throw new IllegalStateException("Gemini 호출 실패: " + path, e);
			}
			if (status >= 200 && status < 300) {
				return responseBody;
			}
			boolean retryable = status == 429 || status == 500 || status == 503;
			if (retryable && attempt < MAX_ATTEMPTS) {
				long wait = retryBaseMillis * attempt;
				log.warn("gemini HTTP {} — {}ms 후 재시도 ({}/{})", status, wait, attempt, MAX_ATTEMPTS);
				sleep(wait);
				continue;
			}
			if (status == 429) {
				throw new LlmQuotaExhaustedException("Gemini 429 재시도 소진 — 일 한도로 간주, 잔여 이월");
			}
			throw new IllegalStateException("Gemini HTTP " + status + ": " + abbreviate(responseBody));
		}
		throw new IllegalStateException("도달 불가");
	}

	static String requestBody(ObjectMapper om, String systemInstruction, String userText,
			InlineImage image, String schemaJson, int maxOutputTokens) {
		ObjectNode root = om.createObjectNode();
		root.putObject("systemInstruction").putArray("parts").addObject().put("text", systemInstruction);
		ArrayNode parts = root.putArray("contents").addObject().put("role", "user").putArray("parts");
		if (image != null) {
			ObjectNode inline = parts.addObject().putObject("inlineData");
			inline.put("mimeType", image.mimeType());
			inline.put("data", Base64.getEncoder().encodeToString(image.data()));
		}
		parts.addObject().put("text", userText);
		ObjectNode gen = root.putObject("generationConfig");
		gen.put("temperature", 0);
		gen.put("responseMimeType", "application/json");
		gen.set("responseSchema", om.readTree(schemaJson));
		gen.put("maxOutputTokens", maxOutputTokens);
		return om.writeValueAsString(root);
	}

	/** File API 업로드(2단계 resumable) — 백필 JSONL 전용. */
	@Override
	public String uploadFile(byte[] jsonl, String displayName) {
		try {
			HttpRequest start = HttpRequest.newBuilder(URI.create(baseUrl + "/upload/v1beta/files"))
					.header("x-goog-api-key", apiKey)
					.header("X-Goog-Upload-Protocol", "resumable")
					.header("X-Goog-Upload-Command", "start")
					.header("X-Goog-Upload-Header-Content-Length", String.valueOf(jsonl.length))
					.header("X-Goog-Upload-Header-Content-Type", "application/jsonl")
					.header("Content-Type", "application/json")
					.POST(HttpRequest.BodyPublishers.ofString(
							"{\"file\":{\"display_name\":\"" + displayName + "\"}}"))
					.build();
			HttpResponse<String> started = http.send(start, HttpResponse.BodyHandlers.ofString());
			String uploadUrl = started.headers().firstValue("x-goog-upload-url")
					.orElseThrow(() -> new IllegalStateException(
							"업로드 URL 헤더 없음: HTTP " + started.statusCode()));
			HttpRequest put = HttpRequest.newBuilder(URI.create(uploadUrl))
					.header("X-Goog-Upload-Command", "upload, finalize")
					.header("X-Goog-Upload-Offset", "0")
					.POST(HttpRequest.BodyPublishers.ofByteArray(jsonl))
					.build();
			HttpResponse<String> done = http.send(put, HttpResponse.BodyHandlers.ofString());
			if (done.statusCode() < 200 || done.statusCode() >= 300) {
				throw new IllegalStateException("파일 업로드 실패 HTTP " + done.statusCode());
			}
			return om.readTree(done.body()).path("file").path("name").asString();
		} catch (java.io.IOException | InterruptedException e) {
			throw new IllegalStateException("파일 업로드 실패", e);
		}
	}

	@Override
	public String createBatch(String model, String inputFileName, String displayName) {
		String body = """
				{"batch":{"display_name":"%s","input_config":{"file_name":"%s"}}}"""
				.formatted(displayName, inputFileName);
		String res = send("/v1beta/models/" + model + ":batchGenerateContent", body);
		return om.readTree(res).path("name").asString();
	}

	@Override
	public String getBatch(String batchName) {
		return get("/v1beta/" + batchName, "배치 조회");
	}

	@Override
	public String downloadFile(String fileName) {
		return get("/download/v1beta/" + fileName + ":download?alt=media", "결과 다운로드");
	}

	private String get(String path, String what) {
		try {
			HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + path))
					.header("x-goog-api-key", apiKey).GET().build();
			HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
			if (res.statusCode() < 200 || res.statusCode() >= 300) {
				throw new IllegalStateException(what + " 실패 HTTP " + res.statusCode());
			}
			return res.body();
		} catch (java.io.IOException | InterruptedException e) {
			throw new IllegalStateException(what + " 실패", e);
		}
	}

	private synchronized void pace() {
		long now = System.currentTimeMillis();
		long wait = nextAllowedAt - now;
		if (wait > 0) {
			sleep(wait);
		}
		nextAllowedAt = Math.max(now, nextAllowedAt) + paceIntervalMillis;
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
