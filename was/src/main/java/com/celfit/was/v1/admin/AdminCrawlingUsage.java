package com.celfit.was.v1.admin;

import java.math.BigDecimal;

/**
 * GET /v1/admin/users/{id}/crawling-usage 응답(2026-08-12 프론트 요청서 §2-1) — envelope data.
 * 네 필드 전부 0 이상의 유한한 숫자여야 한다(프론트 parseCrawlingUsage가 아니면 장애로 취급).
 * 콜 범위는 브랜드 태그 모니터링(brand_call_count) + 캠페인·콘텐츠 모니터링(target_call_count,
 * 2026-08-12 범위 확장)이며, totalCalls는 두 파이프라인 모두 과거 콜 소급 백필을 포함한 누적이다
 * (원형 적재 개시 07-30 이후분 — V20260812153000·V20260812161000).
 */
public record AdminCrawlingUsage(long totalCalls, long monthCalls, long todayCalls,
		BigDecimal unitPriceUsd) {

	public static AdminCrawlingUsage empty(BigDecimal unitPriceUsd) {
		return new AdminCrawlingUsage(0, 0, 0, unitPriceUsd);
	}
}
