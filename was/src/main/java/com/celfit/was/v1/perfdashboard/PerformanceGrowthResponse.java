package com.celfit.was.v1.perfdashboard;

import java.util.List;

/**
 * 성과 대시보드 시계열 집계 응답(스펙 2026-08-28 §5) — 개요 탭(요약 스트립·계정 성장·계정 비교)
 * 전용. 값이 없는 합계는 0이 아니라 null(FE 결측 구분 — 조회수 0과 피드 미제공을 가른다).
 *
 * @param granularity day | week | month
 * @param accounts 계정별 시리즈 — 항상 포함(계정 비교 차트·CSV 재료).
 * @param points 총계 시리즈(individual 미귀속 포함).
 */
public record PerformanceGrowthResponse(
		String granularity,
		List<AccountSeries> accounts,
		List<Point> points) {

	/** 계정 1개의 시리즈 — points의 버킷 경계·개수는 총계와 동일하다(차트 축 공유). */
	public record AccountSeries(String brandAccountId, List<Point> points) {
	}

	/**
	 * 버킷 1개 — start·end는 KST 달력일(양끝 포함). 결측 규칙(08-06 계약): 합계는 아는 값만 더하고
	 * 하나도 모르면 null, 못 더한 것은 카운트로 노출한다.
	 *
	 * <p>양끝 버킷의 라벨은 <b>요청 구간과의 교집합</b>이다 — 주·월 버킷 중간에서 잘린 구간이면
	 * start·end가 온전한 버킷 경계보다 좁게 나온다(집계 범위를 정직하게 반영,
	 * {@link PerformanceGrowthAggregator} javadoc). 라벨 길이가 곧 부분 버킷 식별자다.
	 *
	 * @param start 버킷 시작일(YYYY-MM-DD) — 요청 {@code from}으로 클램프될 수 있다.
	 * @param end 버킷 종료일(YYYY-MM-DD, 양끝 포함) — 요청 {@code to}로 클램프될 수 있다.
	 * @param contentCount 버킷 내 게시물 수(스냅샷 유무 무관 — 차트 지표 중 하나).
	 * @param followersSum 게시물별 작성자 팔로워 합(참여율 분모) — 아는 것만.
	 * @param viewsMissingCount 조회수 미상(피드 null·스냅샷 없음) 수.
	 * @param likesHiddenCount 좋아요 숨김 관측 수.
	 * @param followersMissingCount 작성자 팔로워 미확인 수.
	 */
	public record Point(
			String start, String end,
			int contentCount,
			Long views, Long likes, Long comments,
			Long followersSum,
			int viewsMissingCount,
			int likesHiddenCount,
			int followersMissingCount) {
	}
}
