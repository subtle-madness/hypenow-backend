package com.celfit.contract.analysis;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 서빙 콘텐츠 1행 (미러: analytics.v_contents → contents).
 * hypeScore: 스펙 5.4 산식 0~100 — 릴스 = cbrt(도달×참여질×신선도)×100,
 * 피드 = cbrt(팔로워ER축²×신선도)×100 (신선도는 미러 갱신 시점 기준).
 * 릴스인데 조회수 NULL이면 NULL (정렬은 NULLS LAST).
 * metricCapturedAt: 지표 고정(+3일) 스냅샷의 수집 시각 (스펙 updatedAt 재료).
 */
public record Content(String shortCode, String accountHandle, String thumbnailUrl, String caption,
		OffsetDateTime postedAt, String contentType, BigDecimal videoDuration, String originalUrl,
		Long views, Long likes, Long comments, Long hypeScore, OffsetDateTime metricCapturedAt) {
}
