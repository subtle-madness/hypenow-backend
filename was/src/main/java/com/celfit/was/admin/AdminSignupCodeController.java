package com.celfit.was.admin;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 가입 코드 일괄 적재 API(설계 2026-07-20) — POST /admin/signup-codes.
 * 인증은 SecurityConfig의 @Order(0) 토큰 체인(Bearer CODES_API_KEY)이 담당. 검증·저장은 서비스로 위임.
 */
@RestController
public class AdminSignupCodeController {

	private final AdminSignupCodeService service;

	public AdminSignupCodeController(AdminSignupCodeService service) {
		this.service = service;
	}

	@PostMapping("/admin/signup-codes")
	public SignupCodeCreateResponse create(@RequestBody SignupCodeCreateRequest request) {
		return service.create(request);
	}
}
