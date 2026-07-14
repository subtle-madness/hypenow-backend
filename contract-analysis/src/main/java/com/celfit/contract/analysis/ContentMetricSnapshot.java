package com.celfit.contract.analysis;

import java.time.OffsetDateTime;

/**
 * 지표 스냅샷 1행 (미러: analytics.v_content_metric_snapshots → content_metric_snapshots).
 * 게시물 × 수집 시점 1행 — as-of 조회·추이의 재료. id = raw_post_detail의 id.
 * hypeScore: 릴스=조회수, 피드=좋아요+댓글 (Content.hypeScore와 동일 규칙).
 */
public record ContentMetricSnapshot(Long id, String shortCode, OffsetDateTime capturedAt,
		Long views, Long likes, Long comments, Long hypeScore) {
}
