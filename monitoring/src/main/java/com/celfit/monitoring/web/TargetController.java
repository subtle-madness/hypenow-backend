package com.celfit.monitoring.web;

import com.celfit.monitoring.service.RegistrationService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 캠페인 명령 API — 계약 §2. 인증 없음: 접근 통제는 전용 도커 네트워크(monitoring-net) 소속이 강제한다.
 * 토큰·헤더 검사를 여기에 추가하지 말 것(계약 §1 — 연결이 되면 곧 인가된 호출자다).
 */
@RestController
@RequestMapping("/api/targets")
public class TargetController {

	private final RegistrationService registration;

	public TargetController(RegistrationService registration) {
		this.registration = registration;
	}

	/** 등록 — 동기로 첫 수집까지 하고 응답(권고 타임아웃 10s). 같은 registrationKey 재호출은 201이 아니라 200. */
	@PostMapping
	public ResponseEntity<RegisterResponse> register(@RequestBody RegisterRequest request) {
		var result = registration.register(request.toCommand());
		return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
				.body(new RegisterResponse(result.targetId(), result.status(), result.firstSnapshot()));
	}
}
