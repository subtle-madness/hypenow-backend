package com.celfit.analytics.analyze;

import java.math.BigDecimal;

/** raw v_analysis_baseline 1행 — 분석 시점 스냅샷 재료. */
public record Baseline(Long recentReelsAvgViews, Integer rankInRecentReels, Integer recentReelsCount,
		Integer recentContentsCount, BigDecimal recent12AvgEngagementRate,
		Long recent12AvgLikeCount, Long recent12AvgCommentCount,
		Integer categoryTopPercentile, Long categoryAvgViews, Long categorySampleSize) {
}
