package com.celfit.was.v1.perfdashboard;

import java.util.List;

/**
 * 인플루언서 집계 1행(스펙 2026-08-27 §4, FE 제안 셰이프 그대로) — 값이 없는 합계는 0이 아니라
 * null(계약 무결성 규칙 #1 + FE 결측 구분 요구). 합 0(전부 관측됐는데 0)과 null(전부 미제공)을
 * FE가 다르게 그린다 — 피드 게시물은 views가 항상 null이다.
 *
 * @param postCount 필터 통과 게시물 전체 수(스냅샷 유무 무관 — 지표 합산 모수와 다르다).
 * @param likesKnownCount 좋아요를 아는(숨김 아님) 게시물 수 — likes 합계의 모수.
 * @param latestPostAt 최신 업로드일(YYYY-MM-DD), 업로드일이 전부 미상이면 null.
 * @param ratedFollowers 참여율 분모 — 팔로워·좋아요·댓글을 모두 아는 게시물의 팔로워 합
 *     (게시물마다 1회씩). 참여율 자체는 내리지 않는다 — 재평균 불가를 피한다(FE 규칙 ②).
 * @param ratedEngaged 참여율 분자 — 같은 대상 게시물의 (좋아요 + 댓글) 합.
 * @param brandAccountIds 필터 통과 게시물이 귀속된 브랜드 id의 등장 순 distinct 목록
 *     (미귀속 게시물뿐이면 빈 목록).
 */
public record PerformanceInfluencerResponse(
		String handle,
		String displayName,
		String profileImageUrl,
		Long followers,
		int postCount,
		int sponsoredCount,
		int likesKnownCount,
		String latestPostAt,
		Long views, Long likes, Long comments,
		Long ratedFollowers, Long ratedEngaged,
		List<String> brandAccountIds) {
}
