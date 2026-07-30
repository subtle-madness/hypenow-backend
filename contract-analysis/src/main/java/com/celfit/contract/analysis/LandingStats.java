package com.celfit.contract.analysis;

import java.time.OffsetDateTime;

/**
 * 랜딩 통계 1행 (미러: analytics.v_landing_stats → landing_stats).
 * 모수는 자격 팔로워 구간 계정(500~49,999 — crawler qualify.min/max-followers와 정합)과 그 콘텐츠
 * — 07-29 하한 3,000→500 확장(나노 포함, ARCHITECTURE §7). totalViews·avgViews는 릴스만(피드는
 * 조회수 미공개). followers* 는 구간별 계정 수 — %·합계 100 보정은 소비자(was) 표현 계층 몫.
 * updatedAt = 미러 실행 시각.
 *
 * <p>컬럼명: followers3k10k는 대문자가 없어 toSnakeCase가 그대로 통과시킨다 —
 * 뷰·DDL 컬럼도 언더스코어 없는 {@code followers3k10k}여야 한다(§4-3).
 *
 * <p>컴포넌트 순서: followers500to3k가 updatedAt 뒤(마지막)인 게 정본 — expand 단계(V47 ADD
 * COLUMN)로 미러 테이블 끝에 붙었고, MirrorJob·FlywaySchemaTest가 뷰=record=DDL 순서를 대조한다.
 */
public record LandingStats(Long contentsCount, Long influencersCount, Long totalViews, Long avgViews,
		Long followers3k10k, Long followers10k30k, Long followers30k50k, OffsetDateTime updatedAt,
		Long followers500to3k) {
}
