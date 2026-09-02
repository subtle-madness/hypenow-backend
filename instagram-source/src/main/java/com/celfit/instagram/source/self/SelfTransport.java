package com.celfit.instagram.source.self;

import java.util.Map;

/** 자체크롤 전송 심 — get/post 둘 다. 프로덕션은 SelfHttpClient, 테스트는 fake. */
public interface SelfTransport {
	SelfResponse get(String url, ProxyTier tier, Map<String, String> headers);

	SelfResponse post(String url, String formBody, ProxyTier tier, Map<String, String> headers);
}
