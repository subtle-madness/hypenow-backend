package com.celfit.contract.analysis;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 인플루언서 상세 계정 요약 1행 (미러: analytics.v_account_summaries → account_summaries).
 * celfit-front AccountReport의 결정(비LLM) 지표 — 산식은 스펙 2026-07-13-c1-account-detail-design.md §3.
 * metric: 'views'|'likes' — 조회수 데이터 부족 계정의 기준 지표 폴백. 트렌드·광고 비교가 이 축을 따른다.
 * avgErPct: 계정 평균 ER(팔로워 분모, %) — 게시물 ER(조회수 분모)과 정의가 다르다.
 */
public record AccountSummary(String handle, Long followers, Long followsCount, Long postsCount,
		String biography, Long analyzedCount, Long viewsCount, String metric, Long avgViews,
		BigDecimal viewsPerFollower, BigDecimal avgErPct, Long avgLikes, Long avgComments,
		String trendDirection, Integer trendChangePct, Long trendOlderAvg, Long trendNewerAvg,
		Long sponsoredCount, Long organicAvg, Long adAvg, Integer adDropPct,
		Long comparisonOrganicCount, Long comparisonAdCount, OffsetDateTime lastAdPostedAt,
		OffsetDateTime lastPostedAt, BigDecimal avgIntervalDays) {
}
