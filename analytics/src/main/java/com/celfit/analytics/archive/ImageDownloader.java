package com.celfit.analytics.archive;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** CDN 이미지 다운로드 포트 — 테스트는 fake, 기본 구현은 http(). */
public interface ImageDownloader {

	Downloaded fetch(String url) throws Exception;

	record Downloaded(byte[] bytes, String contentType) {
	}

	/** 기본 구현 — 인스타 CDN GET (AnthropicContentAttributeAnalyzer.download 관용구). */
	static ImageDownloader http() {
		HttpClient http = HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(5))
				.followRedirects(HttpClient.Redirect.NORMAL)
				.build();
		return url -> {
			HttpRequest req = HttpRequest.newBuilder(URI.create(url))
					.timeout(Duration.ofSeconds(20)).GET().build();
			HttpResponse<byte[]> res = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
			if (res.statusCode() / 100 != 2) {
				throw new IllegalStateException("다운로드 실패 HTTP " + res.statusCode() + ": " + url);
			}
			// 인스타 CDN은 jpeg/webp 혼재 — 미상은 jpeg로 간주 (기존 분석기 관용구)
			return new Downloaded(res.body(),
					res.headers().firstValue("Content-Type").orElse("image/jpeg"));
		};
	}
}
