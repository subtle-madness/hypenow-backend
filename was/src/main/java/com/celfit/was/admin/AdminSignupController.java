package com.celfit.was.admin;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 가입 코드 조회 API(설계 2026-07-19) — GET /admin/signups.
 * 발송 표시 변경(설계 2026-07-22) — PATCH /admin/signup-codes/{code}. @Order(0) 토큰 체인 매처는
 * 정확히 /admin/signup-codes라 하위 경로는 안 잡는다 — 이 PATCH는 Basic 체인 소속.
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

	@PatchMapping("/admin/signup-codes/{code}")
	public SignupCodeSentResponse updateSent(@PathVariable String code,
			@RequestBody SignupCodeSentRequest request) {
		if (request.isSent() == null) {
			throw new AdminApiException(400, "isSent가 필요합니다.");
		}
		if (repository.updateIsSent(code, request.isSent()) == 0) {
			throw new AdminApiException(404, "존재하지 않는 코드입니다: " + code);
		}
		return new SignupCodeSentResponse(code, request.isSent());
	}
}
