package com.celfit.was.v1.admin;

import com.celfit.was.auth.AppUserDetails;
import com.celfit.was.v1.common.ApiResponse;
import java.math.BigDecimal;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 어드민 크롤링 비용 API 3종 — 유저별 사용량·전역 단가 수정(2026-08-12) + 전역 비용 요약(2026-08-13).
 * 인가는 SecurityConfig(/v1/admin/** hasRole ADMIN + 신선도 필터)가 처리한다.
 *
 * <p><b>유저별 GET은 404를 내지 않는다</b>: 프론트는 404를 "엔드포인트 미배포"(카드 열화)로 해석하므로,
 * 크롤링 이력이 없는 유저는 물론 존재하지 않는·숫자가 아닌 id도 200 + 전부 0으로 응답한다
 * (유저 존재 검증은 이 카드가 붙는 유저 상세 GET이 이미 담당 — 여기서 중복 판정하지 않는다).
 */
@RestController
public class AdminCrawlingCostController {

	private static final Logger log = LoggerFactory.getLogger(AdminCrawlingCostController.class);

	private final AdminCrawlingUsageService service;
	private final AdminCrawlingCostSummaryService summaryService;

	public AdminCrawlingCostController(AdminCrawlingUsageService service,
			AdminCrawlingCostSummaryService summaryService) {
		this.service = service;
		this.summaryService = summaryService;
	}

	@GetMapping("/v1/admin/users/{id}/crawling-usage")
	public ApiResponse<AdminCrawlingUsage> usage(@PathVariable String id) {
		long userId;
		try {
			userId = Long.parseLong(id);
		} catch (NumberFormatException e) {
			// 존재할 수 없는 id — 이력 없는 유저와 같은 응답(404 금지 계약).
			return ApiResponse.ok(AdminCrawlingUsage.empty(service.currentUnitPrice()));
		}
		return ApiResponse.ok(service.usageFor(userId));
	}

	/**
	 * 전역 크롤링 비용(설계 2026-08-13) — 서비스 전체의 콜·비용을 세 구간과 파이프라인별로.
	 * 유저 스코프가 없어 파라미터가 없다. 이 GET도 404·500을 내지 않는다 —
	 * 못 읽은 구간은 응답의 sources[].available=false로 드러난다.
	 */
	@GetMapping("/v1/admin/crawling-cost/summary")
	public ApiResponse<AdminCrawlingCostSummary> summary() {
		return ApiResponse.ok(summaryService.summary());
	}

	/** body 파싱 실패(비숫자·NaN 성격 문자열)는 V1ExceptionAdvice가 400 VALIDATION_FAILED로 접는다. */
	@PutMapping("/v1/admin/crawling-cost/unit-price")
	public ApiResponse<Map<String, BigDecimal>> updateUnitPrice(@RequestBody UnitPriceRequest request,
			@AuthenticationPrincipal AppUserDetails admin) {
		BigDecimal saved = service.updateUnitPrice(request.unitPriceUsd());
		// 전역 설정 변경 감사 흔적 — app.admin_audit_logs는 act-as(대상 유저 필수) 전용 스키마라
		// 애플리케이션 로그로 남긴다(Loki {service="was"} 조회축).
		log.info("크롤링 전역 단가 변경 — admin={} unitPriceUsd={}", admin.getUserId(), saved.toPlainString());
		return ApiResponse.ok(Map.of("unitPriceUsd", saved));
	}

	public record UnitPriceRequest(BigDecimal unitPriceUsd) {
	}
}
