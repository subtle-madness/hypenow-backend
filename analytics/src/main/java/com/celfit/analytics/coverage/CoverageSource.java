package com.celfit.analytics.coverage;

/**
 * raw DB 서빙 모수 타일 — 신 스키마 서빙 뷰(analytics.v_accounts · v_serving_content) 행 수.
 * 미러 타일의 분모(수집→미러 커버리지)가 된다. 모수 필터(뷰티 인플루언서)는 뷰가 정본 — 여기서 중복하지 않는다.
 */
public record CoverageSource(long accounts, long contents) {
}
