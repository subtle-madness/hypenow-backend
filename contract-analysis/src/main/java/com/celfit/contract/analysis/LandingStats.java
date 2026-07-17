package com.celfit.contract.analysis;

import java.time.OffsetDateTime;

/**
 * 랜딩 통계 1행 (미러: analytics.v_landing_stats → landing_stats).
 * 모수는 마이크로 구간 계정(팔로워 3,000~49,999)과 그 콘텐츠 — 랜딩 카피·스펙 분포와 모수 일치(2026-07-17 확정).
 * totalViews·avgViews는 릴스만(피드는 조회수 미공개). followers* 는 구간별 계정 수 —
 * %·합계 100 보정은 소비자(was) 표현 계층 몫. updatedAt = 미러 실행 시각.
 *
 * <p>컬럼명: followers3k10k는 대문자가 없어 toSnakeCase가 그대로 통과시킨다 —
 * 뷰·DDL 컬럼도 언더스코어 없는 {@code followers3k10k}여야 한다(§4-3).
 */
public record LandingStats(Long contentsCount, Long influencersCount, Long totalViews, Long avgViews,
		Long followers3k10k, Long followers10k30k, Long followers30k50k, OffsetDateTime updatedAt) {
}
