package com.celfit.instagram.source;

/**
 * 수집 라우팅 관측 훅 — 모듈은 Micrometer를 모른다. monitoring이 구현해 Micrometer에 기록한다.
 * path=논리 경로(fetchPost 등, 유한집합), backend=self|hiker,
 * outcome=ok|partial|hardgate|notfound|fallback:<class>. partial=self 성공이지만 댓글 미완주
 * (CommentsFetch.complete=false, F9) — 서킷·폴백 판단에는 영향 없고 관측 전용.
 */
@FunctionalInterface
public interface InstagramSourceMetrics {

	void record(String path, String backend, String outcome);

	/**
	 * 소요시간 관측 훅 — Hiker HTTP에는 이미 external.call 타이머가 있지만(TimedHikerHttp) 자체크롤
	 * (self) 경로가 그걸 우회해 지연 관측이 비어 있었다. record와 같은 (path, backend, outcome) 태그로
	 * 라우팅 계층 자체에서 duration을 남겨 그 공백을 메운다. 기본 구현은 무기록 — 기존 구현체(NOOP 등)가
	 * 이 메서드를 몰라도 깨지지 않는다(추상 메서드는 여전히 record 하나뿐이라 @FunctionalInterface 유지).
	 */
	default void recordDuration(String path, String backend, String outcome, long elapsedNanos) {}

	/** 무기록 기본(테스트·미배선). */
	InstagramSourceMetrics NOOP = (path, backend, outcome) -> {};
}
