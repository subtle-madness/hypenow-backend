package com.celfit.was.v1.account;

import com.celfit.was.auth.LoginRequest;
import com.celfit.was.auth.UserAgentParser;
import com.celfit.was.auth.UserProfile;
import com.celfit.was.auth.UserRepository;
import com.celfit.was.v1.common.ApiResponse;
import com.celfit.was.v1.common.V1ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * /v1/auth 가입·로그인·로그아웃(스펙 6.15/6.16) — 구 /api/auth(AuthController)와 병존.
 * 인증 관용구(AuthenticationManager·HttpSessionSecurityContextRepository·UA attribute)는 동일,
 * 계약만 v1 envelope + 스펙 3.2 에러 코드다. UserSummary의 name·userType은 세션(AppUserDetails —
 * 안정 형상이라 프로필 미보유)에 없으므로 가입은 insertProfile RETURNING, 로그인은 SELECT로 채운다.
 * 가입 코드는 배치 1회용(app.signup_codes, 설계 2026-07-19) — 단일 공용 코드(app_setting)는 폐기.
 */
@RestController
public class V1AuthController {

	/** 이메일 중복 확인 상한(분당·IP) — 디바운스 500ms 전제라 가입(10회)보다 느슨, 열거 남용은 차단. */
	private static final int EMAIL_AVAILABILITY_PER_MINUTE = 30;

	private final SignupValidator signupValidator;
	private final RateLimiter rateLimiter;
	private final UserRepository userRepository;
	private final SignupCodeRepository signupCodeRepository;
	private final SignupService signupService;
	private final AuthenticationManager authenticationManager;
	private final SignupEventRecorder signupEventRecorder;
	private final SecurityContextRepository securityContextRepository = new HttpSessionSecurityContextRepository();

	public V1AuthController(SignupValidator signupValidator, RateLimiter rateLimiter,
			UserRepository userRepository, SignupCodeRepository signupCodeRepository,
			SignupService signupService, AuthenticationManager authenticationManager,
			SignupEventRecorder signupEventRecorder) {
		this.signupValidator = signupValidator;
		this.rateLimiter = rateLimiter;
		this.userRepository = userRepository;
		this.signupCodeRepository = signupCodeRepository;
		this.signupService = signupService;
		this.authenticationManager = authenticationManager;
		this.signupEventRecorder = signupEventRecorder;
	}

	@PostMapping("/v1/auth/signup")
	@ResponseStatus(HttpStatus.CREATED)
	public ApiResponse<UserSummary> signup(@RequestBody SignupRequest request,
			HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
		// 가입 시도 추적(2026-07-29) — 요청 1건당 1행, 내부 어느 단계까지 갔는지 progress로 남긴다
		String eventEmail = request.email() == null ? "" : UserRepository.normalizeEmail(request.email());
		List<String> progress = new ArrayList<>();
		try {
			ApiResponse<UserSummary> response = doSignup(request, httpRequest, httpResponse, progress);
			signupEventRecorder.record(eventEmail, SignupEventRecorder.OUTCOME_OK,
					httpRequest.getRemoteAddr(), detail(request, progress, null, response.data().id()));
			return response;
		} catch (V1ApiException e) {
			signupEventRecorder.record(eventEmail, e.code(), httpRequest.getRemoteAddr(),
					detail(request, progress, e.getMessage(), null));
			throw e;
		} catch (RuntimeException e) {
			// register 이후 자동 로그인 등에서 터지는 500 — "유저는 생겼는데 응답은 실패"가 progress로 구분된다
			signupEventRecorder.record(eventEmail, "INTERNAL_ERROR", httpRequest.getRemoteAddr(),
					detail(request, progress, e.toString(), null));
			throw e;
		}
	}

	private ApiResponse<UserSummary> doSignup(SignupRequest request, HttpServletRequest httpRequest,
			HttpServletResponse httpResponse, List<String> progress) {
		// 가입 남용 차단(스펙 4절) — 계정이 없는 단계라 키는 IP 단위
		if (!rateLimiter.tryAcquire("signup:" + httpRequest.getRemoteAddr())) {
			throw V1ApiException.rateLimited();
		}
		progress.add("rate_limit");
		// 빠른 실패(검증 전) — 원자적 소진 보장은 register 안의 claim이 담당
		requireUsableCode(request.signupCode());
		progress.add("signup_code");
		signupValidator.validate(request);
		progress.add("validation");
		// 이메일 소유권 인증 게이트는 제거(2026-07-29) — 클로즈베타 정책 변경, 가입 관문은 1회용 코드만
		UserProfile profile = signupService.register(request);
		progress.add("register");

		// 가입 직후 자동 로그인 — 방금 저장한 자격증명이라 실패할 수 없는 경로(실패 시 500이 맞다)
		Authentication authResult = authenticationManager.authenticate(
				UsernamePasswordAuthenticationToken.unauthenticated(request.email(), request.password()));
		progress.add("auto_login");
		establishSession(authResult, httpRequest, httpResponse);
		progress.add("session");
		return ApiResponse.ok(UserSummary.from(profile));
	}

	/** 이벤트 detail — 비밀번호는 절대 넣지 않는다. progress는 통과한 내부 단계의 순서 기록. */
	private Map<String, Object> detail(SignupRequest request, List<String> progress, String message,
			String userId) {
		Map<String, Object> detail = new LinkedHashMap<>();
		detail.put("progress", List.copyOf(progress));
		detail.put("signupCode", request.signupCode());
		if (message != null) {
			detail.put("message", message);
		}
		if (userId != null) {
			detail.put("userId", userId);
		}
		return detail;
	}

	/**
	 * 가입 코드 사전 검증(클로즈베타 관문 UX) — 관문 통과 시점에 즉시 유효성만 확인, 소진하지 않는다.
	 * 선점은 가입 트랜잭션에서 일어나므로 통과 후 가입 시점에 코드가 이미 쓰였을 수 있고, 그때 가입이 403이다.
	 */
	@PostMapping("/v1/auth/signup-code/verify")
	public ApiResponse<SignupCodeVerifyResponse> verifySignupCode(@RequestBody SignupCodeVerifyRequest request,
			HttpServletRequest httpRequest) {
		// 무차별 대입 방지 — 익명 표면이라 키는 IP 단위(기본 분당 10회)
		if (!rateLimiter.tryAcquire("signup-code-verify:" + httpRequest.getRemoteAddr())) {
			throw V1ApiException.rateLimited();
		}
		requireUsableCode(request.code());
		return ApiResponse.ok(new SignupCodeVerifyResponse(true));
	}

	/**
	 * 가입 전 이메일 중복 확인(스펙 6.24) — 위저드 2스텝 디바운스 호출. 비교 기준은 6.15 가입과 동일
	 * (UserRepository가 lower 정규화). 존재 여부 노출은 6.15의 409와 동일 수준이라 추가 마스킹 없음.
	 */
	@PostMapping("/v1/auth/email-availability")
	public ApiResponse<EmailAvailabilityResponse> emailAvailability(
			@RequestBody EmailAvailabilityRequest request, HttpServletRequest httpRequest) {
		if (!rateLimiter.tryAcquire("email-availability:" + httpRequest.getRemoteAddr(),
				EMAIL_AVAILABILITY_PER_MINUTE)) {
			throw V1ApiException.rateLimited();
		}
		signupValidator.requireEmail(request.email());
		return ApiResponse.ok(new EmailAvailabilityResponse(
				userRepository.findByEmail(request.email()).isEmpty()));
	}

	@PostMapping("/v1/auth/login")
	public ApiResponse<UserSummary> login(@RequestBody LoginRequest request,
			HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
		// 키는 lower 정규화 이메일(저장 규칙과 동일) — 대소문자 변형으로 계정 차원 제한을 우회하지 못하게
		String email = request.email() == null ? "" : UserRepository.normalizeEmail(request.email());
		if (!rateLimiter.tryAcquire("login:" + email + "|" + httpRequest.getRemoteAddr())) {
			throw V1ApiException.rateLimited();
		}
		try {
			Authentication authResult = authenticationManager.authenticate(
					UsernamePasswordAuthenticationToken.unauthenticated(email, request.password()));
			establishSession(authResult, httpRequest, httpResponse);
		} catch (AuthenticationException e) {
			// 이메일 존재 여부 무관 단일 응답 — 계정 존재 탐지(enumeration) 차단
			throw V1ApiException.unauthorized("INVALID_CREDENTIALS", "이메일 또는 비밀번호를 확인해 주세요.");
		}
		UserProfile profile = userRepository.findProfileByEmail(email)
				.orElseThrow(() -> V1ApiException.unauthorized("INVALID_CREDENTIALS", "이메일 또는 비밀번호를 확인해 주세요."));
		return ApiResponse.ok(UserSummary.from(profile));
	}

	/** 204 본문 없음 — envelope 예외(스펙 3.1). 미로그인 상태여도 멱등 204. */
	@PostMapping("/v1/auth/logout")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void logout(HttpServletRequest httpRequest) {
		HttpSession session = httpRequest.getSession(false);
		if (session != null) {
			session.invalidate();
		}
		SecurityContextHolder.clearContext();
	}

	/** 미발급·소진 코드 단일 응답 — 발급 여부를 구분해 주지 않는다(코드 존재 탐지 차단). 빈 테이블이면 전원 차단(fail-closed 유지). */
	private void requireUsableCode(String code) {
		if (!signupCodeRepository.isUsable(code)) {
			throw V1ApiException.forbidden("INVALID_SIGNUP_CODE", "존재하지 않거나 이미 사용된 코드입니다.");
		}
	}

	/** 인증 성공 → SecurityContext 세션 저장 + 세션 목록 표기용 UA attribute(Task 1 규약, 스펙 6.14). */
	private void establishSession(Authentication authResult, HttpServletRequest httpRequest,
			HttpServletResponse httpResponse) {
		SecurityContext context = SecurityContextHolder.createEmptyContext();
		context.setAuthentication(authResult);
		SecurityContextHolder.setContext(context);
		securityContextRepository.saveContext(context, httpRequest, httpResponse);

		HttpSession session = httpRequest.getSession(true);
		String ua = httpRequest.getHeader("User-Agent");
		session.setAttribute("session.browser", UserAgentParser.browser(ua));
		session.setAttribute("session.os", UserAgentParser.os(ua));
	}
}
