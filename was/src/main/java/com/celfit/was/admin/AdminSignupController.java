package com.celfit.was.admin;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 가입 코드 조회 API(설계 2026-07-19) — GET /admin/signups.
 * 발송 표시·super 승격 변경(설계 2026-07-22 → 2026-08-04 확장) — PATCH /admin/signup-codes/{code}.
 * 인증은 07-23부터 두 경로 모두 @Order(0) 토큰 체인(Bearer CODES_API_KEY)이 담당한다 —
 * 어드민 FE가 키 헤더 방식만 써서 Basic에서 전환(SecurityConfig 매처 참조). 여기선 인증 검사를 하지 않는다.
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
	public SignupCodePatchResponse patch(@PathVariable String code,
			@RequestBody SignupCodePatchRequest request) {
		if (request.isSent() == null && request.isSuper() == null) {
			throw new AdminApiException(400, "isSent 또는 isSuper 중 하나는 필요합니다.");
		}
		return repository.updatePartial(code, request.isSent(), request.isSuper())
				.orElseThrow(() -> new AdminApiException(404, "존재하지 않는 코드입니다: " + code));
	}
}
