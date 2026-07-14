package com.celfit.contract.analysis;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 서빙 콘텐츠 1행 (미러: analytics.v_contents → contents).
 * hypeScore: 릴스=조회수, 피드=좋아요+댓글. 릴스인데 조회수 NULL이면 NULL (정렬은 NULLS LAST).
 */
public record Content(String shortCode, String accountHandle, String thumbnailUrl, String caption,
		OffsetDateTime postedAt, String contentType, BigDecimal videoDuration, String originalUrl,
		Long views, Long likes, Long comments, Long hypeScore) {
}
