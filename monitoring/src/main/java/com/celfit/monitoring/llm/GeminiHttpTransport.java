package com.celfit.monitoring.llm;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Gemini 실전송 — analytics {@code GeminiHttpApi}의 HTTP 관용구를 monitoring 소유로 축소 이식
 * (모듈 간 공유 금지, 배치·페이싱·이미지 등 브랜드 판정에 불필요한 기능은 이식하지 않음).
 * 429·5xx·IOException은 지수 백오프로 최대 3회 재시도, 그 외 4xx는 즉시 실패한다.
 *
 * <p>2026-08-18 Vertex 전환 후 이 클래스는 SA 키 미설정(GOOGLE_APPLICATION_CREDENTIALS 없음)
 * 환경의 폴백 경로로 존치한다({@link com.celfit.monitoring.config.LlmTransportConfig} 참조).
 */
public final class GeminiHttpTransport implements GeminiHttp {

	private static final Logger log = LoggerFactory.getLogger(GeminiHttpTransport.class);
	private static final String DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com";
	private static final long DEFAULT_RETRY_BASE_MILLIS = 1000;
	private static final int MAX_ATTEMPTS = 3;
	// 에러 응답 본문 로깅 절단 한도 — 08-18 스테이징 429 폭주 실측 계기로 300자에서 상향.
	// 쿼터 메트릭 이름 등 원인 단서가 짧은 절단에 잘리면 진단이 막힌다(common-llm VertexHttpTransport와 동일 판단).
	private static final int ERROR_BODY_LOG_LIMIT = 1000;

	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
	private final String baseUrl;
	private final String apiKey;
	private final long retryBaseMillis;

	public GeminiHttpTransport(String apiKey) {
		this(apiKey, DEFAULT_BASE_URL);
	}

	public GeminiHttpTransport(String apiKey, String baseUrl) {
		this(apiKey, baseUrl, DEFAULT_RETRY_BASE_MILLIS);
	}

	/** retryBaseMillis — 테스트에서 백오프 대기를 줄이려고 주입(analytics GeminiHttpApi와 동형 패턴). */
	public GeminiHttpTransport(String apiKey, String baseUrl, long retryBaseMillis) {
		this.apiKey = apiKey;
		this.baseUrl = baseUrl;
		this.retryBaseMillis = retryBaseMillis;
	}

	@Override
	public String post(String path, String jsonBody) {
		for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
			int status;
			String responseBody;
			try {
				HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + path))
						.timeout(Duration.ofSeconds(60))
						.header("Content-Type", "application/json")
						.header("x-goog-api-key", apiKey)
						.POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
						.build();
				HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
				status = res.statusCode();
				responseBody = res.body();
			} catch (IOException e) {
				// 일시 오류 계열 — 5xx와 동일하게 백오프 재시도
				if (attempt < MAX_ATTEMPTS) {
					log.warn("gemini 호출 IO 오류 — {}회차 재시도 예정: {}", attempt, e.getMessage());
					backoff(attempt);
					continue;
				}
				throw new IllegalStateException("Gemini 호출 실패: " + path, e);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException("Gemini 호출 중단: " + path, e);
			}
			if (status >= 200 && status < 300) {
				return responseBody;
			}
			boolean retryable = status == 429 || (status >= 500 && status < 600);
			if (retryable && attempt < MAX_ATTEMPTS) {
				log.warn("gemini HTTP {} — {}회차 재시도 예정", status, attempt);
				backoff(attempt);
				continue;
			}
			throw new IllegalStateException("Gemini HTTP " + status + ": " + abbreviate(responseBody));
		}
		throw new IllegalStateException("도달 불가");
	}

	private void backoff(int attempt) {
		try {
			Thread.sleep(retryBaseMillis * (1L << attempt));
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("재시도 대기 중 인터럽트", e);
		}
	}

	private static String abbreviate(String s) {
		return s == null ? "(없음)"
				: s.length() > ERROR_BODY_LOG_LIMIT ? s.substring(0, ERROR_BODY_LOG_LIMIT) + "…" : s;
	}
}
