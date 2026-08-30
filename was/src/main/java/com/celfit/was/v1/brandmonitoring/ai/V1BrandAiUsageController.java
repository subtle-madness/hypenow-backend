package com.celfit.was.v1.brandmonitoring.ai;

import com.celfit.was.auth.AppUserDetails;
import com.celfit.was.v1.common.ApiResponse;
import com.celfit.was.v1.common.V1ApiException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 챗 사용량 조회 표면(FE 변경요청서 2026-08-28 §9.2) - 오늘 상한·잔여 횟수·다음 초기화 시각.
 * 프론트가 챗 입력창에 "오늘 12/30회 남음" 같은 안내를 그리는 데 쓴다.
 *
 * <p>킬 스위치·인증은 {@link V1BrandAiMessagesController}와 동일한 조건이다 - 어시스턴트 기능 전체가
 * 하나의 표면이라는 판단은 그대로 유지한다.
 */
@RestController
@RequestMapping("/v1/brand-monitoring/ai")
@ConditionalOnProperty(name = {"monitoring.enabled", "monitoring.brand.ai.enabled"}, havingValue = "true")
public class V1BrandAiUsageController {

	private final AiChatQuota quota;

	public V1BrandAiUsageController(AiChatQuota quota) {
		this.quota = quota;
	}

	@GetMapping("/usage")
	public ApiResponse<AiUsageResponse> usage(@AuthenticationPrincipal AppUserDetails principal) {
		if (principal == null) {
			throw V1ApiException.unauthorized("UNAUTHORIZED", "로그인이 필요해요.");
		}
		return ApiResponse.ok(AiUsageResponse.from(quota.usage(principal.getUserId())));
	}
}
