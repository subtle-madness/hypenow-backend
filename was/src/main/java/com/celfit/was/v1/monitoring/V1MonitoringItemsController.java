package com.celfit.was.v1.monitoring;

import com.celfit.was.auth.AppUserDetails;
import com.celfit.was.v1.common.ApiResponse;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 모니터링 등록 접수 /v1(스펙 6.27) — 인증 필수(SecurityConfig anyRequest().authenticated()가 처리).
 * GET(6.26, 전량 조회)은 어셈블러 후속 태스크가 이 컨트롤러에 추가한다 — 지금은 POST만.
 */
@RestController
public class V1MonitoringItemsController {

	private final V1MonitoringRegistrationService service;

	public V1MonitoringItemsController(V1MonitoringRegistrationService service) {
		this.service = service;
	}

	@PostMapping("/v1/monitoring/items")
	public ResponseEntity<ApiResponse<MonitoringRegistrationResponse>> register(
			@AuthenticationPrincipal AppUserDetails principal, @RequestBody(required = false) Map<String, Object> body) {
		Map<String, Object> fields = body == null ? Map.of() : body;
		MonitoringRegistrationResponse response = service.register(principal.getUserId(), fields);
		return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
	}
}
