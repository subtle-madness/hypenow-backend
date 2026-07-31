package com.celfit.monitoring.image;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * OCI 쓰기 PAR(사전 인증 요청) 어댑터 — analytics {@code com.celfit.analytics.archive.ParImageStore}를
 * 복제하되(모듈 간 import 금지, 클래스 주석 참고) 생성자 동작을 의도적으로 바꿨다: analytics 버전은
 * PAR URL 미설정 시 생성자에서 {@link IllegalStateException}을 던져 기동을 막는다. monitoring은
 * 아직 실사용이 없는 환경(test 등)에서도 서버가 정상 기동해야 하므로 여기서는 던지지 않고 빈 URL을
 * 그대로 들고 있는다 — 미설정 여부는 {@link ProfileImageArchiveJob}이 PAR URL 자체를 보고 no-op으로
 * 처리한다(설계 스펙 §3-1 "PAR 미설정 시: 잡이 로그만 남기고 no-op, 기동은 정상").
 */
public class ParImageStore implements ImageStore {

	private final String parBaseUrl;
	private final HttpClient http;

	/** parBaseUrl이 비어 있어도 예외를 던지지 않는다 — 호출자(ProfileImageArchiveJob)가 no-op을 판단한다. */
	public ParImageStore(String parBaseUrl) {
		this(parBaseUrl, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
	}

	ParImageStore(String parBaseUrl, HttpClient http) {
		this.parBaseUrl = parBaseUrl == null || parBaseUrl.isBlank() || parBaseUrl.endsWith("/")
				? parBaseUrl : parBaseUrl + "/";
		this.http = http;
	}

	@Override
	public void put(String objectPath, byte[] bytes, String contentType, String cacheControl) {
		HttpRequest req = HttpRequest.newBuilder(URI.create(parBaseUrl + objectPath))
				.timeout(Duration.ofSeconds(30))
				.header("Content-Type", contentType)
				.header("Cache-Control", cacheControl)
				.PUT(HttpRequest.BodyPublishers.ofByteArray(bytes))
				.build();
		try {
			HttpResponse<Void> res = http.send(req, HttpResponse.BodyHandlers.discarding());
			if (res.statusCode() / 100 != 2) {
				throw new IllegalStateException("업로드 실패 HTTP " + res.statusCode() + ": " + objectPath);
			}
		} catch (IOException e) {
			throw new IllegalStateException("업로드 실패: " + objectPath, e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("업로드 중단: " + objectPath, e);
		}
	}
}
