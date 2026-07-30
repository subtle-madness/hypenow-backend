package com.celfit.was.v1.monitoring;

import com.celfit.was.auth.AppUserDetails;
import com.celfit.was.v1.common.ApiResponse;
import com.celfit.was.v1.common.KstTimestamps;
import com.celfit.was.v1.common.V1ApiException;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 모니터링 목록 조회(6.26)·등록 접수(6.27)·행 수정(6.29)·취소(6.30) /v1 — 인증 필수(SecurityConfig
 * anyRequest().authenticated()가 처리).
 */
@RestController
public class V1MonitoringItemsController {

	private final V1MonitoringRegistrationService registrationService;
	private final V1MonitoringItemUpdateService updateService;
	private final TrackingItemAssembler assembler;

	public V1MonitoringItemsController(V1MonitoringRegistrationService registrationService,
			V1MonitoringItemUpdateService updateService, TrackingItemAssembler assembler) {
		this.registrationService = registrationService;
		this.updateService = updateService;
		this.assembler = assembler;
	}

	/**
	 * 유저 소유 추적 행 전량 조회(registeredAt ASC·id ASC — MonitoringItemRepository.findByUser가 정렬을
	 * 보장). meta.total은 항상 data.length와 같다(전량 반환 목록, 스펙 1.4). meta.lastCollectedAt·today는
	 * {@link TrackingItemAssembler.AssembledList}가 함께 계산해 온다.
	 */
	@GetMapping("/v1/monitoring/items")
	public ApiResponse<List<TrackingItemResponse>> list(@AuthenticationPrincipal AppUserDetails principal) {
		TrackingItemAssembler.AssembledList assembled = assembler.assembleList(principal.getUserId());
		Map<String, Object> meta = new LinkedHashMap<>();
		meta.put("total", assembled.items().size());
		meta.put("lastCollectedAt", KstTimestamps.toKstIso(assembled.lastCollectedAt()));
		meta.put("today", assembled.today().toString());
		return ApiResponse.ok(assembled.items(), meta);
	}

	@PostMapping("/v1/monitoring/items")
	public ResponseEntity<ApiResponse<MonitoringRegistrationResponse>> register(
			@AuthenticationPrincipal AppUserDetails principal,
			// Swagger 문서 전용 스키마 지정 — 런타임 역직렬화는 아래 Map<String,Object> 그대로(MonitoringItemRequestDocs 참조).
			@io.swagger.v3.oas.annotations.parameters.RequestBody(
					content = @Content(schema = @Schema(implementation = MonitoringItemRequestDocs.Register.class)))
			@RequestBody(required = false) Map<String, Object> body) {
		Map<String, Object> fields = body == null ? Map.of() : body;
		MonitoringRegistrationResponse response = registrationService.register(principal.getUserId(), fields);
		return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
	}

	/** 편집 대상은 기간(trackingDays)과 캠페인(campaignId/campaignName)뿐(6.29). */
	@PatchMapping("/v1/monitoring/items/{itemId}")
	public ApiResponse<MonitoringItemPatchResponse> patch(@AuthenticationPrincipal AppUserDetails principal,
			@PathVariable String itemId,
			// Swagger 문서 전용 스키마 지정 — 런타임 역직렬화는 아래 Map<String,Object> 그대로(MonitoringItemRequestDocs 참조).
			@io.swagger.v3.oas.annotations.parameters.RequestBody(
					content = @Content(schema = @Schema(implementation = MonitoringItemRequestDocs.Patch.class)))
			@RequestBody(required = false) Map<String, Object> body) {
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
