package com.celfit.was.v1.account;

import com.celfit.was.auth.UserRepository;
import com.celfit.was.mail.MailSendException;
import com.celfit.was.v1.common.V1ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * /v1/auth/email-verification 발송·확인(설계 2026-07-18) — 가입 전 이메일 소유권 인증.
 * 익명 표면(화이트리스트 /v1/auth/**)이라 레이트리밋이 1차 방어. 이메일은 저장 규칙과 동일 lower 정규화.
 */
@RestController
public class V1EmailVerificationController {

	public record SendRequest(String email) {
	}

	public record ConfirmRequest(String email, String code) {
	}

	private final EmailVerificationService emailVerificationService;
	private final SignupValidator signupValidator;
	private final RateLimiter rateLimiter;
	private final UserRepository userRepository;

	public V1EmailVerificationController(EmailVerificationService emailVerificationService,
			SignupValidator signupValidator, RateLimiter rateLimiter, UserRepository userRepository) {
		this.emailVerificationService = emailVerificationService;
		this.signupValidator = signupValidator;
		this.rateLimiter = rateLimiter;
		this.userRepository = userRepository;
	}

	@PostMapping("/v1/auth/email-verification/send")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void send(@RequestBody SendRequest request, HttpServletRequest httpRequest) {
		String email = request.email() == null ? "" : UserRepository.normalizeEmail(request.email());
		// 이메일당 분당 1회(재발송 쿨다운) + IP당 분당 5회 — 익명 발송 남용 차단
		if (!rateLimiter.tryAcquire("email-verify-send:" + email, 1)
				|| !rateLimiter.tryAcquire("email-verify-send-ip:" + httpRequest.getRemoteAddr(), 5)) {
			throw V1ApiException.rateLimited();
		}
		signupValidator.requireEmail(request.email());
		if (userRepository.findByEmail(email).isPresent()) {
			throw V1ApiException.conflict("EMAIL_ALREADY_EXISTS", "이미 가입된 이메일이에요. 로그인해 주세요.");
		}
		try {
			emailVerificationService.sendCode(email);
		} catch (MailSendException e) {
			throw V1ApiException.badGateway("EMAIL_SEND_FAILED", "인증 메일 발송에 실패했어요. 잠시 후 다시 시도해 주세요.");
		}
	}

	@PostMapping("/v1/auth/email-verification/confirm")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void confirm(@RequestBody ConfirmRequest request, HttpServletRequest httpRequest) {
		if (!rateLimiter.tryAcquire("email-verify-confirm:" + httpRequest.getRemoteAddr(), 10)) {
			throw V1ApiException.rateLimited();
		}
		signupValidator.requireEmail(request.email());
		emailVerificationService.confirm(UserRepository.normalizeEmail(request.email()), request.code());
	}
}
