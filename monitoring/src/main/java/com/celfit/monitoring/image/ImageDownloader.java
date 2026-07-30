package com.celfit.monitoring.image;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * CDN 이미지 다운로드 포트 — analytics {@code com.celfit.analytics.archive.ImageDownloader}를
 * 그대로 복제(~10줄, 순수 JDK). 모듈 간 Java 공유는 계약 모듈(contract-analysis, 분석 결과
 * record·enum 전용)만 허용이라 HTTP 어댑터인 이 클래스는 그 모듈에 넣을 수 없다 — 90줄 안팎의
 * 중복이 새 공유 모듈보다 시스템 경계 원칙에 맞다(설계 스펙 §3-1).
 */
public interface ImageDownloader {

	Downloaded fetch(String url) throws Exception;

	record Downloaded(byte[] bytes, String contentType) {
	}

	/** 기본 구현 — 인스타 CDN GET. */
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
			// 인스타 CDN은 jpeg/webp 혼재 — 미상은 jpeg로 간주 (analytics 관용구와 동일).
			return new Downloaded(res.body(),
					res.headers().firstValue("Content-Type").orElse("image/jpeg"));
		};
	}
}
