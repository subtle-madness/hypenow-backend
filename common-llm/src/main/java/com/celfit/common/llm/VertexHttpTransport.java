package com.celfit.common.llm;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Vertex AI 범용 HTTP 전송 — 경로+JSON 본문을 그대로 전달하는 저수준 POST만 제공한다. 프롬프트
 * 구성·응답 파싱·도메인 스키마는 호출자(소비 모듈) 소관이라 이 클래스는 관여하지 않는다
 * (package-info.java 스코프 규칙). 재시도(429/5xx 선형 백오프)·에러 매핑(429 재시도 소진은
 * {@link LlmQuotaExhaustedException})은 analytics {@code VertexHttpApi.send()}와 동형이다.
 *
 * <p>08-18 이식 — 이식 출처: analytics/src/main/java/com/celfit/analytics/llm/VertexHttpApi.java
 * (send() 메서드만; 배치·GCS 업로드 등 도메인 전용 기능은 반입하지 않음 — monitoring이 그
 * 기능을 쓰지 않고, 반입 시 "전송 계층만" 원칙을 벗어난다).
 *
 * <p>에러 응답 본문 로깅 절단 한도는 {@value #DEFAULT_ERROR_BODY_LOG_LIMIT}자로 넉넉히 잡는다
 * (08-18 스테이징 429 폭주 실측 계기 — 쿼터 메트릭 이름 등 원인 단서가 짧은 절단에 잘리면 진단이
 * 막힌다. 기존 monitoring {@code GeminiHttpTransport}의 300자보다 3배 이상 큼).
 */
public final class VertexHttpTransport {

	private static final Logger log = LoggerFactory.getLogger(VertexHttpTransport.class);
	public static final String DEFAULT_BASE_URL = "https://aiplatform.googleapis.com";
	private static final int DEFAULT_MAX_ATTEMPTS = 6;
	private static final int DEFAULT_ERROR_BODY_LOG_LIMIT = 2000;
	/** 기존 사용처(monitoring·야간 배치) 무영향 기본값 — 사람이 기다리지 않는 경로라 넉넉히 잡는다. */
	private static final int DEFAULT_REQUEST_TIMEOUT_SECONDS = 120;

	private final Supplier<String> token;
	private final String baseUrl;
	private final long retryBaseMillis;
	private final int maxAttempts;
	private final int errorBodyLogLimit;
	private final int requestTimeoutSeconds;
	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

	public VertexHttpTransport(Supplier<String> token, String baseUrl, long retryBaseMillis) {
		this(token, baseUrl, retryBaseMillis, DEFAULT_MAX_ATTEMPTS, DEFAULT_ERROR_BODY_LOG_LIMIT,
				DEFAULT_REQUEST_TIMEOUT_SECONDS);
	}

	/** 재시도 횟수·에러 로그 절단 한도를 주입하는 생성자 — 테스트에서 재시도 대기·바디 길이를 줄이는 용도. */
	public VertexHttpTransport(Supplier<String> token, String baseUrl, long retryBaseMillis,
			int maxAttempts, int errorBodyLogLimit) {
		this(token, baseUrl, retryBaseMillis, maxAttempts, errorBodyLogLimit, DEFAULT_REQUEST_TIMEOUT_SECONDS);
	}

	/**
	 * 요청 타임아웃까지 지정하는 전체 생성자(C2) — 사람이 동기로 기다리는 경로(브랜드 AI 챗 등)는
	 * 기본 120초가 너무 길다: 1회 호출이 오래 매달리면 위의 재시도·백오프까지 겹쳐 벽시계 예산을
	 * 순식간에 태운다. 이 필드는 전송 계층 설정이라 common-llm 소관이고, 기존 생성자들은 기본값을
	 * 유지해 monitoring 등 기존 사용처는 무영향이다.
	 */
	public VertexHttpTransport(Supplier<String> token, String baseUrl, long retryBaseMillis,
			int maxAttempts, int errorBodyLogLimit, int requestTimeoutSeconds) {
		this.token = token;
		this.baseUrl = baseUrl;
		this.retryBaseMillis = retryBaseMillis;
		this.maxAttempts = maxAttempts;
		this.errorBodyLogLimit = errorBodyLogLimit;
		this.requestTimeoutSeconds = requestTimeoutSeconds;
	}

	/** path는 baseUrl 이후 전체 경로(예: "/v1/projects/{p}/locations/{loc}/publishers/google/models/{m}:generateContent"). */
	public String post(String path, String jsonBody) {
		for (int attempt = 1; attempt <= maxAttempts; attempt++) {
			int status;
			String responseBody;
			try {
				HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + path))
						.timeout(Duration.ofSeconds(requestTimeoutSeconds))
						.header("Content-Type", "application/json")
						.header("Authorization", "Bearer " + token.get())
						.POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
						.build();
				HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
				status = res.statusCode();
				responseBody = res.body();
			} catch (IOException e) {
				// 일시 오류 계열 — 5xx와 동일하게 백오프 재시도(monitoring GeminiHttpTransport와 동형)
				if (attempt < maxAttempts) {
					log.warn("vertex 호출 IO 오류 — {}회차 재시도 예정: {}", attempt, e.getMessage());
					sleep(retryBaseMillis * attempt);
					continue;
				}
				throw new IllegalStateException("Vertex 호출 실패: " + path, e);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException("Vertex 호출 중단: " + path, e);
			}
			if (status >= 200 && status < 300) {
				return responseBody;
			}
			boolean retryable = status == 429 || status == 500 || status == 503;
			if (retryable && attempt < maxAttempts) {
				long wait = retryBaseMillis * attempt;
				log.warn("vertex HTTP {} — {}ms 후 재시도 ({}/{}): {}", status, wait, attempt, maxAttempts,
						abbreviate(responseBody));
				sleep(wait);
				continue;
			}
			if (status == 429) {
				throw new LlmQuotaExhaustedException(
						"Vertex 429 재시도 소진 — 일시 용량 부족, 잔여 이월: " + abbreviate(responseBody));
			}
			throw new IllegalStateException("Vertex HTTP " + status + ": " + abbreviate(responseBody));
		}
		throw new IllegalStateException("도달 불가");
	}

	private static void sleep(long millis) {
		try {
			Thread.sleep(millis);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("대기 중 인터럽트", e);
		}
	}

	private String abbreviate(String s) {
		return s == null ? "(없음)" : s.length() > errorBodyLogLimit ? s.substring(0, errorBodyLogLimit) + "…" : s;
	}
}
