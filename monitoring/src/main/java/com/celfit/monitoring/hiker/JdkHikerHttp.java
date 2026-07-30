package com.celfit.monitoring.hiker;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * HikerAPI 실제 전송 — crawler의 동일 관용구를 monitoring 소유로 재작성했다(모듈 간 공유 금지).
 *
 * <p>일시 오류(5xx·IO·타임아웃)는 짧은 백오프로 재시도한다(스펙 §2-3): 예전에는 첫 실패로 그
 * 계정의 하루치 수집이 통째로 비었고, 상태도 안 바뀌어 아무도 눈치채지 못했다.
 * 404({@link SubjectNotFoundException})는 결정적 부재라 재시도하지 않는다 — 다시 쏴도 결과가 같고
 * 종결(FAILED)만 늦어진다.
 */
@Component
public class JdkHikerHttp implements HikerHttp {

	private static final Logger log = LoggerFactory.getLogger(JdkHikerHttp.class);

	private final HttpClient client = HttpClient.newHttpClient();
	private final String baseUrl;
	private final String apiKey;
	private final Duration timeout;
	private final int maxRetries;
	private final Duration retryBackoff;

	public JdkHikerHttp(HikerProperties props) {
		// 키가 없어도 앱은 부팅한다 — 실제 호출 시점(get)에만 검증한다.
		this.baseUrl = props.baseUrl() == null ? "https://api.hikerapi.com" : props.baseUrl();
		this.apiKey = props.apiKey();
		this.timeout = props.requestTimeout() == null ? Duration.ofSeconds(15) : props.requestTimeout();
		this.maxRetries = props.maxRetries() == null ? 2 : Math.max(0, props.maxRetries());
		this.retryBackoff = props.retryBackoff() == null ? Duration.ofSeconds(2) : props.retryBackoff();
	}

	@Override
	public String get(String path) {
		if (apiKey == null || apiKey.isBlank()) {
			throw new HikerFetchException("HIKER_API_KEY 미설정");
		}
		HikerFetchException last = null;
		for (int attempt = 0; attempt <= maxRetries; attempt++) {
			if (attempt > 0) {
				// 선형 백오프 — 상대가 순간 과부하일 때 같은 간격으로 몰아치지 않게 회차만큼 벌린다.
				sleep(retryBackoff.multipliedBy(attempt));
				log.warn("Hiker 재시도 {}/{} — {}", attempt, maxRetries, path);
			}
			try {
				return send(path);
			} catch (HikerFetchException e) {
				last = e;   // SubjectNotFoundException은 HikerFetchException이 아니라 여기서 안 잡힌다(결정적 — 즉시 전파)
			}
		}
		throw last;
	}

	private String send(String path) {
		HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + path))
				.timeout(timeout)
				.header("x-access-key", apiKey)
				.header("accept", "application/json")
				.GET().build();
		try {
			HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
			if (res.statusCode() == 404) {
				// 대상 부재(계정 삭제·개명 등) — 재시도 무의미, 호출자가 종결 처리
				throw new SubjectNotFoundException("Hiker 404: " + res.body());
			}
			if (res.statusCode() >= 300) {
				throw new HikerFetchException("Hiker HTTP " + res.statusCode() + ": " + res.body());
			}
			return res.body();
		} catch (IOException e) {
			throw new HikerFetchException("Hiker 요청 실패: " + e.getMessage(), e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new HikerFetchException("Hiker 요청 중단", e);
		}
	}

	private static void sleep(Duration duration) {
		if (duration.isZero() || duration.isNegative()) {
			return;
		}
		try {
			Thread.sleep(duration.toMillis());
		} catch (InterruptedException e) {
			// 인터럽트는 종료 신호다 — 삼키면 셧다운이 백오프 시간만큼 늘어진다.
			Thread.currentThread().interrupt();
			throw new HikerFetchException("Hiker 재시도 대기 중단", e);
		}
	}
}
