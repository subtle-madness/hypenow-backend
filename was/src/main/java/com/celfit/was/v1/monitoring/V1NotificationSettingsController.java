package com.celfit.was.v1.monitoring;

import com.celfit.was.auth.AppUserDetails;
import com.celfit.was.v1.common.ApiResponse;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 알림 설정 /v1(스펙 6.33) — 인증 필수(SecurityConfig anyRequest().authenticated()가 처리).
 * 목록이 아니라 meta는 붙이지 않는다.
 */
@RestController
public class V1NotificationSettingsController {

	private final NotificationSettingsService service;

	public V1NotificationSettingsController(NotificationSettingsService service) {
		this.service = service;
	}

	@GetMapping("/v1/notification-settings")
	public ApiResponse<NotificationSettingsResponse> get(@AuthenticationPrincipal AppUserDetails principal) {
		return ApiResponse.ok(service.get(principal.getUserId()));
	}

	@PatchMapping("/v1/notification-settings")
	public ApiResponse<NotificationSettingsResponse> patch(@AuthenticationPrincipal AppUserDetails principal,
			// Swagger 문서 전용 스키마 지정 — 런타임 역직렬화는 아래 Map<String,Object> 그대로(NotificationSettingsPatchDoc 참조).
			@io.swagger.v3.oas.annotations.parameters.RequestBody(
					content = @Content(schema = @Schema(implementation = NotificationSettingsPatchDoc.Request.class)))
			@RequestBody(required = false) Map<String, Object> body) {
		Map<String, Object> fields = body == null ? Map.of() : body;
		return ApiResponse.ok(service.patch(principal.getUserId(), fields));
	}
}
