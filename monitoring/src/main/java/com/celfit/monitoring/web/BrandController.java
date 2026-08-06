package com.celfit.monitoring.web;

import com.celfit.monitoring.service.BrandRegistrationService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 브랜드 태그 모니터링 등록/탈퇴 API(수집 파이프라인 진입점 — was 조회 API·FE 계약은 범위 밖).
 * 201 신규 / 200 replay / 204 탈퇴(이미 닫힘 포함, 멱등) / 404 미등록·IG 계정 부재 /
 * 400 형식 위반 / 422 비공개 계정 — 예외 매핑은 ApiExceptionHandler 공용.
 */
@RestController
@RequestMapping("/api/brands")
public class BrandController {

	public record BrandRegisterRequest(String username) {}

	public record BrandRegisterResponse(long brandId, String username, Long followers, String status) {}

	private final BrandRegistrationService service;

	public BrandController(BrandRegistrationService service) {
		this.service = service;
	}

	@PostMapping
	public ResponseEntity<BrandRegisterResponse> register(@RequestBody BrandRegisterRequest req) {
		BrandRegistrationService.Result result = service.register(req.username());
		return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
				.body(new BrandRegisterResponse(result.brandId(), result.username(),
						result.followers(), "ACTIVE"));
	}

	@DeleteMapping("/{username}")
	public ResponseEntity<Void> deregister(@PathVariable String username) {
		return switch (service.deregister(username)) {
			// 이미 닫힘도 멱등 204 — was 재시도(타임아웃·크래시 복구)가 안전해야 한다.
			case CLOSED, ALREADY_CLOSED -> ResponseEntity.noContent().build();
			case NOT_FOUND -> ResponseEntity.notFound().build();
		};
	}
}
