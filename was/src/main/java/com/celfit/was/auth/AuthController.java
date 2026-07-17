package com.celfit.was.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** 이메일+비밀번호 인증 — 세션 쿠키(HttpSession)로 로그인 상태를 유지한다. */
@RestController
public class AuthController {

	private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
	private static final int PASSWORD_MIN_LENGTH = 8;

	private final UserRepository userRepository;
	private final PasswordEncoder passwordEncoder;
	private final AuthenticationManager authenticationManager;
	private final SecurityContextRepository securityContextRepository = new HttpSessionSecurityContextRepository();

	public AuthController(UserRepository userRepository, PasswordEncoder passwordEncoder,
			AuthenticationManager authenticationManager) {
		this.userRepository = userRepository;
		this.passwordEncoder = passwordEncoder;
		this.authenticationManager = authenticationManager;
	}

	@PostMapping("/api/auth/signup")
	@ResponseStatus(HttpStatus.CREATED)
	public UserResponse signup(@RequestBody SignupRequest request) {
		if (request.email() == null || !EMAIL_PATTERN.matcher(request.email()).matches()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "이메일 형식이 올바르지 않습니다");
		}
		if (request.password() == null || request.password().length() < PASSWORD_MIN_LENGTH) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "비밀번호는 8자 이상이어야 합니다");
		}
		try {
			AppUser user = userRepository.insert(request.email(), passwordEncoder.encode(request.password()));
			return UserResponse.from(user);
		} catch (DuplicateKeyException e) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 가입된 이메일입니다");
		}
	}

	/** 인증 성공 시 SecurityContext를 세션에 저장한다 — Spring Security 표준 SPA 로그인 관용구. */
	@PostMapping("/api/auth/login")
	public UserResponse login(@RequestBody LoginRequest request, HttpServletRequest httpRequest,
			HttpServletResponse httpResponse) {
		try {
			Authentication authRequest =
					UsernamePasswordAuthenticationToken.unauthenticated(request.email(), request.password());
			Authentication authResult = authenticationManager.authenticate(authRequest);

			SecurityContext context = SecurityContextHolder.createEmptyContext();
			context.setAuthentication(authResult);
			SecurityContextHolder.setContext(context);
			securityContextRepository.saveContext(context, httpRequest, httpResponse);

			// 세션 목록 표기용(스펙 6.14) — 로그인 시점 UA를 1회 파싱해 세션 attribute로 남긴다
			HttpSession session = httpRequest.getSession(true);
			String ua = httpRequest.getHeader("User-Agent");
			session.setAttribute("session.browser", UserAgentParser.browser(ua));
			session.setAttribute("session.os", UserAgentParser.os(ua));

			return UserResponse.from((AppUserDetails) authResult.getPrincipal());
		} catch (AuthenticationException e) {
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다");
		}
	}

	/** 세션 무효화는 AuthController가 직접 한다 — SecurityConfig의 logout()은 비활성화(폼 로그아웃 리다이렉트 방지). */
	@PostMapping("/api/auth/logout")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void logout(HttpServletRequest httpRequest) {
		HttpSession session = httpRequest.getSession(false);
		if (session != null) {
			session.invalidate();
		}
		SecurityContextHolder.clearContext();
	}

	@GetMapping("/api/me")
	public UserResponse me(@AuthenticationPrincipal AppUserDetails principal) {
		return UserResponse.from(principal);
	}
}
