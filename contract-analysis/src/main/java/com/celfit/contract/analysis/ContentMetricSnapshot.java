package com.celfit.contract.analysis;

import java.time.OffsetDateTime;

/**
 * 지표 스냅샷 1행. **07-30 미러 중단** — 소비자(was 레거시 /api)가 사라져
 * MirrorConfig 등록부에서 빠졌다. 이 record는 이제 FlywaySchemaTest의 컬럼↔타입 대조에만 쓰이고,
 * 지표 이력은 raw DB의 analytics.v_content_metric_snapshots를 직접 조회한다(뷰는 존속).
 * 게시물 × 수집 시점 1행 — as-of 조회·추이의 재료. id = raw_post_detail의 id.
 * hypeScore: 0~100. 산식 정본은 analytics.hype_score()(analytics/views/02_serving.sql) —
 * clamp(타입별 앵커 매핑(Q), 0, 100) × 0.5^(경과일/halflife) (Content.hypeScore와 동일 산식이되
 * 경과일만 now()가 아닌 capturedAt 기준 — "그 시점의 점수"). Java에는 계산 로직이 없다.
 * 릴스인데 조회수 NULL이면 NULL.
 */
public record ContentMetricSnapshot(Long id, String shortCode, OffsetDateTime capturedAt,
		Long views, Long likes, Long comments, Long hypeScore) {
}
