package com.celfit.monitoring.web;

import com.celfit.monitoring.service.RegistrationService;
import com.celfit.monitoring.service.TargetCommandService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
	private final TargetCommandService command;

	public TargetController(RegistrationService registration, TargetCommandService command) {
		this.registration = registration;
		this.command = command;
	}

	/** 등록 — 동기로 첫 수집까지 하고 응답(권고 타임아웃 10s). 같은 registrationKey 재호출은 201이 아니라 200. */
	@PostMapping
	public ResponseEntity<RegisterResponse> register(@RequestBody RegisterRequest request) {
		var result = registration.register(request.toCommand());
		return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
				.body(new RegisterResponse(result.targetId(), result.status(), result.firstSnapshot()));
	}

	/** 후보 승인 — TRACKING 전환 + 즉시 1회 수집(권고 타임아웃 10s). */
	@PostMapping("/{id}/candidates/{candidateId}/approve")
	public ApproveResponse approve(@PathVariable long id, @PathVariable long candidateId) {
		var target = command.approve(id, candidateId);
		return new ApproveResponse(target.id(), target.status().name(), target.trackedShortCode());
	}

	/** 후보 기각 — 후보만 닫고 캠페인은 WATCHING 지속. */
	@PostMapping("/{id}/candidates/{candidateId}/reject")
	public RejectResponse reject(@PathVariable long id, @PathVariable long candidateId) {
		var candidate = command.reject(id, candidateId);
		return new RejectResponse(candidate.id(), candidate.status().name());
	}

	/** 기간 연장. */
	@PatchMapping("/{id}")
	public ExtendResponse extend(@PathVariable long id, @RequestBody ExtendRequest request) {
		var target = command.extend(id,
				request.expiresAt() == null ? null : request.expiresAt().toInstant());
		return new ExtendResponse(target.id(), target.expiresAt());
	}

	/** 해지 — CANCELED 전이(물리 삭제 아님). 이미 종결이면 현재 상태 그대로 200(멱등). */
	@DeleteMapping("/{id}")
	public CancelResponse cancel(@PathVariable long id) {
		var target = command.cancel(id);
		return new CancelResponse(target.id(), target.status().name());
	}
}
