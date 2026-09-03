package com.celfit.instagram.source;

/**
 * 수집 라우팅 관측 훅 — 모듈은 Micrometer를 모른다. monitoring이 구현해 Micrometer에 기록한다.
 * path=논리 경로(fetchPost 등, 유한집합), backend=self|hiker,
 * outcome=ok|partial|hardgate|notfound|private|fallback:<class>. private=self가 확정 판정
 * PrivateAccountException을 직접 던진 경우(Hiker 재확인 없이 전파). partial=self 성공이지만 댓글 미완주
 * (CommentsFetch.complete=false, F9) — 서킷·폴백 판단에는 영향 없고 관측 전용.
 */
@FunctionalInterface
public interface InstagramSourceMetrics {

	void record(String path, String backend, String outcome);

	/** 무기록 기본(테스트·미배선). */
	InstagramSourceMetrics NOOP = (path, backend, outcome) -> {};
}
