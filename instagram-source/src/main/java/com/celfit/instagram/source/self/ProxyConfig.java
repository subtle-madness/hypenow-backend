package com.celfit.instagram.source.self;

import java.time.Duration;

/**
 * 자체크롤 프록시 설정 — 순수 값(Spring 무관). 값 주입은 소비 모듈(monitoring)이 한다.
 * geoKr=true면 exit IP를 KR로 핀(전송 성공률·지연 개선). URL 미설정 티어는 null(=직접 연결 폴백).
 */
public record ProxyConfig(String residentialUrl, String mobileUrl, Duration requestTimeout, boolean geoKr) {

	/** 티어의 프록시 URL(geoKr면 __cr.kr 적용). 미설정이면 null. */
	public String urlFor(ProxyTier tier) {
		String url = switch (tier) {
			case RESIDENTIAL -> residentialUrl;
			case MOBILE -> mobileUrl;
		};
		if (url == null || url.isBlank()) {
			return null;
		}
		return geoKr ? ProxyUrls.withCountry(url, "kr") : url;
	}
}
