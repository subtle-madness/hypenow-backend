package com.celfit.analytics.archive;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * OCI 쓰기 PAR(사전 인증 요청) 어댑터 — SDK 없이 HTTP PUT 하나로 업로드.
 * PAR URL은 `.../o/`로 끝나는 쓰기 전용(AnyObjectWrite) URL. Cache-Control은
 * PUT 헤더로 객체 메타데이터에 저장돼 공개 읽기·Vercel 엣지가 그대로 따른다.
 */
public class ParImageStore implements ImageStore {

	private final String parBaseUrl;
	private final HttpClient http;

	public ParImageStore(String parBaseUrl) {
		this(parBaseUrl, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
	}

	ParImageStore(String parBaseUrl, HttpClient http) {
		if (parBaseUrl == null || parBaseUrl.isBlank()) {
			throw new IllegalStateException("analytics.image-par-url 미설정 — 쓰기 PAR URL이 필요하다");
		}
		this.parBaseUrl = parBaseUrl.endsWith("/") ? parBaseUrl : parBaseUrl + "/";
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
