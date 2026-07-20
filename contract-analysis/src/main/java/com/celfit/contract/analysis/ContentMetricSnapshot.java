package com.celfit.contract.analysis;

import java.time.OffsetDateTime;

/**
 * 지표 스냅샷 1행 (미러: analytics.v_content_metric_snapshots → content_metric_snapshots).
 * 게시물 × 수집 시점 1행 — as-of 조회·추이의 재료. id = raw_post_detail의 id.
 * hypeScore: 스펙 5.4 산식 0~100 — 릴스 = cbrt(도달×참여질×신선도)×100,
 * 피드 = cbrt(팔로워ER축²×신선도)×100 (Content.hypeScore와 동일 산식이되
 * 신선도만 now()가 아닌 capturedAt 기준 — "그 시점의 점수").
 * 릴스인데 조회수 NULL이면 NULL.
 */
public record ContentMetricSnapshot(Long id, String shortCode, OffsetDateTime capturedAt,
		Long views, Long likes, Long comments, Long hypeScore) {
}
