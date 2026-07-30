package com.celfit.monitoring.hiker;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.stereotype.Component;

/** HikerAPI 실제 전송 — crawler의 동일 관용구를 monitoring 소유로 재작성했다(모듈 간 공유 금지). */
@Component
public class JdkHikerHttp implements HikerHttp {

	private final HttpClient client = HttpClient.newHttpClient();
	private final String baseUrl;
	private final String apiKey;
	private final Duration timeout;

	public JdkHikerHttp(HikerProperties props) {
		// 키가 없어도 앱은 부팅한다 — 실제 호출 시점(get)에만 검증한다.
		this.baseUrl = props.baseUrl() == null ? "https://api.hikerapi.com" : props.baseUrl();
		this.apiKey = props.apiKey();
		this.timeout = props.requestTimeout() == null ? Duration.ofSeconds(15) : props.requestTimeout();
	}

	@Override
	public String get(String path) {
		if (apiKey == null || apiKey.isBlank()) {
			throw new HikerFetchException("HIKER_API_KEY 미설정");
		}
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
			if (res.statusCode() == 400) {
				// 요청 형식 불량 — share 해소(§2-6)는 이를 SHARE_LINK_UNRESOLVED로 갈아 끼운다.
				throw new HikerBadRequestException("Hiker 400: " + res.body());
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
}
