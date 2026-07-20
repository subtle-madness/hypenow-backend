package com.celfit.was.admin;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 가입 코드 조회 API(설계 2026-07-19) — GET /admin/signups.
 * 인증은 SecurityConfig의 @Order(1) ADMIN Basic 체인이 담당(/admin/** 매처). 여기선 role 검사를 하지 않는다.
 */
@RestController
public class AdminSignupController {

	private final AdminSignupRepository repository;

	public AdminSignupController(AdminSignupRepository repository) {
		this.repository = repository;
	}

	@GetMapping("/admin/signups")
	public List<SignupUsageRow> signups() {
		return repository.findAll();
	}
}
