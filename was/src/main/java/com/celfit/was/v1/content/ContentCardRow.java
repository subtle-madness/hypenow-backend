package com.celfit.was.v1.content;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 카드 조인 결과 1행 — contents ⋈ content_analyses ⋈ accounts (분석 결과끼리, §4-4 허용).
 * 목록(6.1)·recentContents(6.4)가 같은 행 형태를 공유한다.
 */
public record ContentCardRow(
		String shortCode,
		String thumbnailUrl,
		String caption,
		OffsetDateTime postedAt,
		String contentType,
		BigDecimal videoDuration,
		String originalUrl,
		Long views,
		Long likes,
		Long comments,
		Long hypeScore,
		OffsetDateTime metricCapturedAt,
		String mainCategory,
		String subCategoriesJson,
		String adType,
		String brandsJson,
		String productsJson,
		String distributorsJson,
		String handle,
		String displayName,
		String profileImageUrl,
		Long followers) {
}
