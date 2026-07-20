package com.celfit.was.postdetail;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * content_analyses 1행 (분석 층 소유 — was 로컬 읽기 record, §4-4).
 * jsonb 컬럼은 ::text로 받아 어셈블러가 JSON 구조로 파싱한다. VLM 미실행 컬럼은 null 그대로.
 */
public record ContentAnalysisRow(
		OffsetDateTime analyzedAt,
		String aiContentSummary,
		String contentsPattern,
		String aiCommentInsight,
		Long recentReelsAvgViews,
		Integer rankInRecentReels,
		Integer recentReelsCount,
		Integer recentContentsCount,
		BigDecimal recent12AvgEngagementRate,
		Long recent12AvgLikeCount,
		Long recent12AvgCommentCount,
		Integer categoryTopPercentile,
		Long categoryAvgViews,
		Long categorySampleSize,
		String detectedBrandsJson,
		String sponsoredSignalLevel,
		String sponsoredSignalReasonsJson,
		String adDisclosure,
		String detectedProductCategoriesJson,
		String vlmAttributesJson,
		String mainCategory,
		String subCategoriesJson,
		String adType,
		String commentAuthenticityGrade,
		String commentAuthenticityNote) {
}
