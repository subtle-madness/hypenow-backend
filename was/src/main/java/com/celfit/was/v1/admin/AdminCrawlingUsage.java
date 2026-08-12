package com.celfit.was.v1.admin;

import java.math.BigDecimal;

/**
 * GET /v1/admin/users/{id}/crawling-usage 응답(2026-08-12 프론트 요청서 §2-1) — envelope data.
 * 네 필드 전부 0 이상의 유한한 숫자여야 한다(프론트 parseCrawlingUsage가 아니면 장애로 취급).
 * totalCalls는 집계 시작 시점(brand_call_count 배포) 이후 누적이다 — 과거 콜 소급 없음.
 */
public record AdminCrawlingUsage(long totalCalls, long monthCalls, long todayCalls,
		BigDecimal unitPriceUsd) {

	public static AdminCrawlingUsage empty(BigDecimal unitPriceUsd) {
		return new AdminCrawlingUsage(0, 0, 0, unitPriceUsd);
	}
}
