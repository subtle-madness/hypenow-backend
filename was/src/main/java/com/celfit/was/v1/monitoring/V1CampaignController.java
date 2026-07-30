package com.celfit.was.v1.monitoring;

import com.celfit.was.auth.AppUserDetails;
import com.celfit.was.monitoring.CampaignRow;
import com.celfit.was.v1.common.ApiResponse;
import com.celfit.was.v1.common.V1ApiException;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 캠페인 CRUD /v1(스펙 6.31) — 인증 필수(SecurityConfig anyRequest().authenticated()가 처리).
 * body는 다른 v1 컨트롤러 관용구(V1Saved*Controller)를 따라 Map&lt;String,Object&gt;로 받아
 * PATCH의 "키 존재 여부"(containsKey)로 유지/해제를 구분한다.
 */
@RestController
public class V1CampaignController {

	private final V1CampaignService service;

	public V1CampaignController(V1CampaignService service) {
		this.service = service;
	}

	/** 전량 반환, createdAt ASC(스펙 1.4·6.31). meta.total은 data.length와 항상 같아야 한다. */
	@GetMapping("/v1/monitoring/campaigns")
	public ApiResponse<List<CampaignResponse>> list(@AuthenticationPrincipal AppUserDetails principal) {
		List<CampaignResponse> items = service.list(principal.getUserId()).stream()
				.map(CampaignResponse::from)
				.toList();
		Map<String, Object> meta = new LinkedHashMap<>();
		meta.put("total", items.size());
		return ApiResponse.ok(items, meta);
	}

	@PostMapping("/v1/monitoring/campaigns")
	public ResponseEntity<ApiResponse<CampaignResponse>> create(@AuthenticationPrincipal AppUserDetails principal,
			// Swagger 문서 전용 스키마 지정 — 런타임 역직렬화는 아래 Map<String,Object> 그대로(CampaignRequestDocs 참조).
			@io.swagger.v3.oas.annotations.parameters.RequestBody(
					content = @Content(schema = @Schema(implementation = CampaignRequestDocs.Create.class)))
			@RequestBody(required = false) Map<String, Object> body) {
		Map<String, Object> fields = body == null ? Map.of() : body;
		CampaignRow row = service.create(principal.getUserId(), fields.get("name"), fields.get("description"),
				fields.get("startDate"), fields.get("endDate"), fields.get("brand"), fields.get("budget"),
				fields.get("seedingCount"));
		return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(CampaignResponse.from(row)));
	}

	@PatchMapping("/v1/monitoring/campaigns/{campaignId}")
	public ApiResponse<CampaignResponse> patch(@AuthenticationPrincipal AppUserDetails principal,
			@PathVariable String campaignId,
			// Swagger 문서 전용 스키마 지정 — 런타임 역직렬화는 아래 Map<String,Object> 그대로(CampaignRequestDocs 참조).
			@io.swagger.v3.oas.annotations.parameters.RequestBody(
					content = @Content(schema = @Schema(implementation = CampaignRequestDocs.Patch.class)))
			@RequestBody(required = false) Map<String, Object> body) {
		Map<String, Object> fields = body == null ? Map.of() : body;
		CampaignRow row = service.patch(principal.getUserId(), parseCampaignId(campaignId), fields);
		return ApiResponse.ok(CampaignResponse.from(row));
	}

	/** 캠페인만 삭제 — 소속 추적 행 연결 해제는 FK ON DELETE SET NULL(스펙 6.31). 멱등 아님(404 있음). */
	@DeleteMapping("/v1/monitoring/campaigns/{campaignId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@AuthenticationPrincipal AppUserDetails principal, @PathVariable String campaignId) {
		service.delete(principal.getUserId(), parseCampaignId(campaignId));
	}

	/** campaignId는 문자열 path 파라미터라 숫자가 아니면 존재할 수 없는 id → 404로 처리(스펙 6.31). */
	private static long parseCampaignId(String raw) {
		try {
			return Long.parseLong(raw);
		} catch (NumberFormatException e) {
			throw V1ApiException.notFound("캠페인을 찾을 수 없습니다.");
		}
	}
}
