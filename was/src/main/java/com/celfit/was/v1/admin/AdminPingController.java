package com.celfit.was.v1.admin;

import com.celfit.was.v1.common.ApiResponse;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * /v1/admin/** 게이트(어드민 백엔드 API 설계 2026-08-01 §1) 최소 확인용 임시 엔드포인트.
 * 실 어드민 조회 API(§4 users·monitoring·audit-logs)는 후속 구현 몫 — 이 컨트롤러는 SecurityConfig의
 * hasRole("ADMIN") 매처와 act-as 필터의 "/v1/admin/** 무시" 분기를 실제 요청으로 검증하기 위한 자리다.
 */
@RestController
public class AdminPingController {

	@GetMapping("/v1/admin/ping")
	public ApiResponse<Map<String, Boolean>> ping() {
		return ApiResponse.ok(Map.of("ok", true));
	}
}
