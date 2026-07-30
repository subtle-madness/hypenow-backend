package com.celfit.contract.analysis;

import java.time.OffsetDateTime;

/**
 * 지표 스냅샷 1행 (미러: analytics.v_content_metric_snapshots → content_metric_snapshots).
 * 게시물 × 수집 시점 1행 — as-of 조회·추이의 재료. id = raw_post_detail의 id.
 * hypeScore: 0~100. 산식 정본은 analytics.hype_score()(analytics/views/02_serving.sql) —
 * clamp(타입별 앵커 매핑(Q), 0, 100) × 0.5^(경과일/halflife) (Content.hypeScore와 동일 산식이되
 * 경과일만 now()가 아닌 capturedAt 기준 — "그 시점의 점수"). Java에는 계산 로직이 없다.
 * 릴스인데 조회수 NULL이면 NULL.
 */
public record ContentMetricSnapshot(Long id, String shortCode, OffsetDateTime capturedAt,
		Long views, Long likes, Long comments, Long hypeScore) {
}
