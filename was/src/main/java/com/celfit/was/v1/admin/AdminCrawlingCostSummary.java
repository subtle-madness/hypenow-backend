package com.celfit.was.v1.admin;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * GET /v1/admin/crawling-cost/summary 응답(설계 2026-08-13 §2) — envelope data.
 *
 * <p>집계 단위는 <b>유료 요청 1회</b>다(수집 건수 아님). 모니터링 몫은 Hiker HTTP 교환 1번,
 * 크롤러 몫은 crawl_run이 구매한 요청 수 — 집계 지점이 다를 뿐 단위는 같다.
 *
 * <p><b>이 값은 유저별 카드(GET /v1/admin/users/{id}/crawling-usage)의 합과 일치하지 않는다</b> —
 * 공유 브랜드는 유저마다 계상되므로 유저별 합이 더 크다. 실제로 나간 돈은 이쪽이다.
 */
public record AdminCrawlingCostSummary(Totals totals, List<Segment> breakdown,
		BigDecimal unitPriceUsd, List<SourceStatus> sources) {

	/** 세 구간 총계 — 항상 breakdown 전 행의 합과 일치한다(같은 누적기 산출). */
	public record Totals(long totalCalls, long monthCalls, long todayCalls,
			BigDecimal totalCostUsd, BigDecimal monthCostUsd, BigDecimal todayCostUsd) {
	}

	/**
	 * 파이프라인 1구간. 콜이 0이어도 행을 유지한다 — 행이 사라지면 프론트가 "파이프라인이
	 * 없어졌다"와 "안 썼다"를 구분할 수 없다.
	 */
	public record Segment(String key, String label, long totalCalls, long monthCalls, long todayCalls,
			BigDecimal totalCostUsd, BigDecimal monthCostUsd, BigDecimal todayCostUsd) {
	}

	/**
	 * 소스별 가용성·신선도. available=false는 "0"이 아니라 "모름"이라는 신호다.
	 * latestCallOn은 그 소스가 가진 최신 KST 달력일 — 크롤러 몫은 미러(하루 1회)를 타므로
	 * 이 값이 어디까지 반영됐는지를 드러낸다.
	 */
	public record SourceStatus(String key, boolean available, LocalDate latestCallOn) {
	}
}
