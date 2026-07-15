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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
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
 */
@RestController
public class V1AuthController {

	private final SignupValidator signupValidator;
	private final RateLimiter rateLimiter;
	private final UserRepository userRepository;
	private final PasswordEncoder passwordEncoder;
	private final AuthenticationManager authenticationManager;
	private final SecurityContextRepository securityContextRepository = new HttpSessionSecurityContextRepository();

	public V1AuthController(SignupValidator signupValidator, RateLimiter rateLimiter,
			UserRepository userRepository, PasswordEncoder passwordEncoder,
			AuthenticationManager authenticationManager) {
		this.signupValidator = signupValidator;
		this.rateLimiter = rateLimiter;
		this.userRepository = userRepository;
		this.passwordEncoder = passwordEncoder;
		this.authenticationManager = authenticationManager;
	}

	@PostMapping("/v1/auth/signup")
	@ResponseStatus(HttpStatus.CREATED)
	public ApiResponse<UserSummary> signup(@RequestBody SignupRequest request,
			HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
		signupValidator.validate(request);

		UserProfile profile;
		try {
			profile = userRepository.insertProfile(request, passwordEncoder.encode(request.password()));
		} catch (DuplicateKeyException e) {
			throw V1ApiException.conflict("EMAIL_ALREADY_EXISTS", "이미 가입된 이메일이에요. 로그인해 주세요.");
		}

		// 가입 직후 자동 로그인 — 방금 저장한 자격증명이라 실패할 수 없는 경로(실패 시 500이 맞다)
		Authentication authResult = authenticationManager.authenticate(
				UsernamePasswordAuthenticationToken.unauthenticated(request.email(), request.password()));
		establishSession(authResult, httpRequest, httpResponse);
		return ApiResponse.ok(UserSummary.from(profile));
	}

	@PostMapping("/v1/auth/login")
	public ApiResponse<UserSummary> login(@RequestBody LoginRequest request,
			HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
		String email = request.email() == null ? "" : request.email();
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
