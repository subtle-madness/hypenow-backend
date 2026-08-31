package com.celfit.instagram.source;

/**
 * 수집 라우팅 관측 훅 — 모듈은 Micrometer를 모른다. monitoring이 구현해 Micrometer에 기록한다.
 * path=논리 경로(fetchPost 등, 유한집합), backend=self|hiker, outcome=ok|hardgate|notfound|fallback:<class>.
 */
@FunctionalInterface
public interface InstagramSourceMetrics {

	void record(String path, String backend, String outcome);

	/** 무기록 기본(테스트·미배선). */
	InstagramSourceMetrics NOOP = (path, backend, outcome) -> {};
}
