package com.celfit.was.v1.monitoring;

import com.celfit.was.auth.AppUserDetails;
import com.celfit.was.v1.common.ApiResponse;
import com.celfit.was.v1.common.V1ApiException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 모니터링 등록 접수(6.27)·행 수정(6.29)·취소(6.30) /v1 — 인증 필수(SecurityConfig
 * anyRequest().authenticated()가 처리). GET(6.26, 전량 조회)은 어셈블러 후속 태스크가 이
 * 컨트롤러에 추가한다.
 */
@RestController
public class V1MonitoringItemsController {

	private final V1MonitoringRegistrationService registrationService;
	private final V1MonitoringItemUpdateService updateService;

	public V1MonitoringItemsController(V1MonitoringRegistrationService registrationService,
			V1MonitoringItemUpdateService updateService) {
		this.registrationService = registrationService;
		this.updateService = updateService;
	}

	@PostMapping("/v1/monitoring/items")
	public ResponseEntity<ApiResponse<MonitoringRegistrationResponse>> register(
			@AuthenticationPrincipal AppUserDetails principal, @RequestBody(required = false) Map<String, Object> body) {
		Map<String, Object> fields = body == null ? Map.of() : body;
		MonitoringRegistrationResponse response = registrationService.register(principal.getUserId(), fields);
		return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
	}

	/** 편집 대상은 기간(trackingDays)과 캠페인(campaignId/campaignName)뿐(6.29). */
	@PatchMapping("/v1/monitoring/items/{itemId}")
	public ApiResponse<MonitoringItemPatchResponse> patch(@AuthenticationPrincipal AppUserDetails principal,
			@PathVariable String itemId, @RequestBody(required = false) Map<String, Object> body) {
		Map<String, Object> fields = body == null ? Map.of() : body;
		MonitoringItemPatchResponse response = updateService.patch(principal.getUserId(), parseItemId(itemId), fields);
		return ApiResponse.ok(response);
	}

	/** 모니터링 취소(6.30) — 승인·거절 액션 폐기 이후 유일한 유저 액션. 본문 없음, 비가역. */
	@PostMapping("/v1/monitoring/items/{itemId}/cancel")
	public ApiResponse<TrackingItemResponse> cancel(@AuthenticationPrincipal AppUserDetails principal,
			@PathVariable String itemId) {
		TrackingItemResponse response = updateService.cancel(principal.getUserId(), parseItemId(itemId));
		return ApiResponse.ok(response);
	}

	/** itemId는 문자열 path 파라미터라 숫자가 아니면 존재할 수 없는 id → 404(V1CampaignController와 동일 관용구). */
	private static long parseItemId(String raw) {
		try {
			return Long.parseLong(raw);
		} catch (NumberFormatException e) {
			throw V1ApiException.notFound("대상을 찾을 수 없습니다.");
		}
	}
}
