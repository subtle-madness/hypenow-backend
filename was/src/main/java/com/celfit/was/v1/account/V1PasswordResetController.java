package com.celfit.was.v1.account;

import com.celfit.was.auth.AppUser;
import com.celfit.was.auth.UserRepository;
import com.celfit.was.mail.MailSendException;
import com.celfit.was.v1.common.ApiResponse;
import com.celfit.was.v1.common.V1ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * /v1/auth/password-reset 3종(프론트 요청 2026-08-12, 스펙 6.34 예정) — 로그인 불가 유저의
 * 자가 복구. 익명 표면(화이트리스트 /v1/auth/**)이라 레이트리밋이 1차 방어. 가입 안 된
 * 이메일은 404로 즉시 알린다 — 존재 노출은 email-availability(6.24)와 동일 수준이고,
 * 오지 않는 메일을 기다리는 오타 유저의 손실이 더 크다(요청서 3절 결정).
 */
@RestController
public class V1PasswordResetController {

	private static final Logger log = LoggerFactory.getLogger(V1PasswordResetController.class);

	public record SendRequest(String email) {
	}

	public record ConfirmRequest(String email, String code) {
	}

	public record ResetRequest(String resetToken, String newPassword) {
	}

	public record ConfirmResponse(String resetToken, int expiresIn) {
	}

	private final PasswordResetService passwordResetService;
	private final SignupValidator signupValidator;
	private final RateLimiter rateLimiter;
	private final UserRepository userRepository;
	private final SessionService sessionService;
	private final PasswordEncoder passwordEncoder;

	public V1PasswordResetController(PasswordResetService passwordResetService,
			SignupValidator signupValidator, RateLimiter rateLimiter, UserRepository userRepository,
			SessionService sessionService, PasswordEncoder passwordEncoder) {
		this.passwordResetService = passwordResetService;
		this.signupValidator = signupValidator;
		this.rateLimiter = rateLimiter;
		this.userRepository = userRepository;
		this.sessionService = sessionService;
		this.passwordEncoder = passwordEncoder;
	}

	@PostMapping("/v1/auth/password-reset/send")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void send(@RequestBody SendRequest request, HttpServletRequest httpRequest) {
		String email = request.email() == null ? "" : UserRepository.normalizeEmail(request.email());
		// 요청서 3절 정책 — 쿨다운 60초(이메일당 분당 1회) + 이메일당 시간당 5회 + IP당 시간당 20회
		if (!rateLimiter.tryAcquire("pw-reset-send:" + email, 1)
				|| !rateLimiter.tryAcquire("pw-reset-send-1h:" + email, 5, 60)
				|| !rateLimiter.tryAcquire("pw-reset-send-ip-1h:" + httpRequest.getRemoteAddr(), 20, 60)) {
			throw V1ApiException.rateLimited();
		}
		signupValidator.requireEmail(request.email());
		if (userRepository.findByEmail(email).isEmpty()) {
			throw V1ApiException.notFound("USER_NOT_FOUND", "가입되지 않은 이메일이에요.");
		}
		try {
			passwordResetService.sendCode(email);
		} catch (MailSendException e) {
			throw V1ApiException.badGateway("EMAIL_SEND_FAILED", "메일 발송에 실패했어요. 잠시 후 다시 시도해 주세요.");
		}
	}

	@PostMapping("/v1/auth/password-reset/confirm")
	public ApiResponse<ConfirmResponse> confirm(@RequestBody ConfirmRequest request,
			HttpServletRequest httpRequest) {
		// 코드 무차별 대입 2차 방어(1차는 오입력 5회) — 익명 표면이라 키는 IP 단위
		if (!rateLimiter.tryAcquire("pw-reset-confirm:" + httpRequest.getRemoteAddr())) {
			throw V1ApiException.rateLimited();
		}
		signupValidator.requireEmail(request.email());
		PasswordResetService.IssuedToken issued = passwordResetService
				.confirm(UserRepository.normalizeEmail(request.email()), request.code());
		return ApiResponse.ok(new ConfirmResponse(issued.resetToken(), issued.expiresIn()));
	}

	/** 성공 시 자동 로그인 없음(Set-Cookie 미발급) — 프론트가 로그인 화면으로 보낸다(요청서 5절). */
	@PostMapping("/v1/auth/password-reset")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void reset(@RequestBody ResetRequest request, HttpServletRequest httpRequest) {
		if (!rateLimiter.tryAcquire("pw-reset:" + httpRequest.getRemoteAddr())) {
			throw V1ApiException.rateLimited();
		}
		// 검증을 토큰 소비보다 먼저 — 검증 실패로 토큰이 죽으면 유저가 처음부터 다시 해야 한다
		signupValidator.validatePassword(request.newPassword());
		String email = passwordResetService.consumeToken(request.resetToken());
		AppUser user = userRepository.findByEmail(email)
				.orElseThrow(() -> V1ApiException.badRequest("INVALID_RESET_TOKEN",
						"인증 시간이 만료됐어요. 처음부터 다시 진행해 주세요."));
		userRepository.updatePasswordHash(user.id(), passwordEncoder.encode(request.newPassword()));
		// 탈취 세션 차단(요청서 5절). DB(password_hash)가 정본 — 정리 실패로 500을 내리면
		// 클라이언트가 "재설정 실패"로 오해하므로 best-effort(6.13 관용구, V1MeController 참조)
		try {
			sessionService.deleteAll(email);
		} catch (RuntimeException e) {
			log.warn("비밀번호 재설정은 완료, 세션 무효화 실패 — userId={}", user.id(), e);
		}
	}
}
