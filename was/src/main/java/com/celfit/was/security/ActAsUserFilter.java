package com.celfit.was.security;

import com.celfit.was.auth.AppUser;
import com.celfit.was.auth.AppUserDetails;
import com.celfit.was.auth.UserRepository;
import com.celfit.was.v1.admin.AdminAuditLogRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * X-Act-As-User(어드민 백엔드 API 설계 2026-08-01 §2) — 어드민이 특정 유저 화면을 그대로 보는 사칭 필터.
 * @Order(2) 세션 체인의 {@code AuthorizationFilter} 뒤에 등록한다: 인가 판정(hasRole 등)은 이미
 * 어드민 본인 authentication으로 끝났으므로, 여기서 principal을 대상 유저로 바꿔도 인가를 잠그지 않는다.
 *
 * <p><b>SecurityContext 원복이 핵심 안전장치다.</b> {@code SecurityContextHolderFilter}는 이 필터보다
 * 앞서 실행되지만 컨텍스트 저장(세션 반영)은 체인이 다 풀린 뒤(자신의 finally)에 일어난다 — 즉 우리
 * 필터가 끝나기 전까지 홀더에 남아 있는 authentication이 그대로 세션에 저장될 수 있다. try/finally로
 * 원래 SecurityContext를 되돌리지 않으면 어드민 세션이 대상 유저 권한으로 영구 치환되는 사고가 난다.
 *
 * <p>의존성은 {@link ObjectProvider}로 받는다(LastActiveAtFilter와 동일 이유 — @WebMvcTest 슬라이스
 * 대부분엔 UserRepository·AdminAuditLogRepository 빈이 없다). 헤더가 없으면 어차피 조회조차
 * 안 하니 대부분의 슬라이스는 영향이 없고, 실제로 헤더를 보내는 act-as 테스트만 빈을 채워 넣으면 된다.
 */
public class ActAsUserFilter extends OncePerRequestFilter {

	private static final Logger log = LoggerFactory.getLogger(ActAsUserFilter.class);

	static final String HEADER_NAME = "X-Act-As-User";
	private static final String ADMIN_PATH_PREFIX = "/v1/admin/";
	private static final Pattern NUMERIC_ID = Pattern.compile("^[0-9]+$");

	private static final String FORBIDDEN_WRITE_BODY =
			"{\"success\":false,\"data\":null,\"error\":{\"code\":\"FORBIDDEN\",\"message\":\"유저 뷰는 조회 전용이에요.\"}}";
	private static final String NOT_FOUND_BODY =
			"{\"success\":false,\"data\":null,\"error\":{\"code\":\"NOT_FOUND\",\"message\":\"대상을 찾을 수 없습니다.\"}}";

	private final ObjectProvider<UserRepository> userRepositoryProvider;
	private final ObjectProvider<AdminAuditLogRepository> auditLogRepositoryProvider;

	public ActAsUserFilter(ObjectProvider<UserRepository> userRepositoryProvider,
			ObjectProvider<AdminAuditLogRepository> auditLogRepositoryProvider) {
		this.userRepositoryProvider = userRepositoryProvider;
		this.auditLogRepositoryProvider = auditLogRepositoryProvider;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String targetIdHeader = request.getHeader(HEADER_NAME);
		if (targetIdHeader == null || targetIdHeader.isBlank()) {
			chain.doFilter(request, response);
			return;
		}

		SecurityContext originalContext = SecurityContextHolder.getContext();
		Authentication authentication = originalContext.getAuthentication();
		if (!isAdmin(authentication)) {
			// 운영 도메인 프록시 우회 등 비어드민(익명 포함)의 사칭 시도 — 헤더는 무시하고 통과, 신호만 남긴다.
			log.warn("어드민이 아닌 세션의 X-Act-As-User 시도 — sessionUserId={}, targetUserId={}, path={}",
					sessionUserIdOrAnonymous(authentication), targetIdHeader, request.getRequestURI());
			chain.doFilter(request, response);
			return;
		}

		String path = request.getRequestURI();
		if (path.startsWith(ADMIN_PATH_PREFIX)) {
			// 어드민 표면은 사칭 의미가 없다 — 인가는 이미 어드민 본인으로 끝났다.
			chain.doFilter(request, response);
			return;
		}

		String method = request.getMethod();
		if (!(HttpMethod.GET.matches(method) || HttpMethod.HEAD.matches(method))) {
			response.setHeader(HttpHeaders.ALLOW, "GET, HEAD");
			writeJson(response, HttpStatus.METHOD_NOT_ALLOWED, FORBIDDEN_WRITE_BODY);
			return;
		}

		if (!NUMERIC_ID.matcher(targetIdHeader.trim()).matches()) {
			writeJson(response, HttpStatus.NOT_FOUND, NOT_FOUND_BODY);
			return;
		}
		long targetUserId = Long.parseLong(targetIdHeader.trim());

		UserRepository userRepository = userRepositoryProvider.getIfAvailable();
		if (userRepository == null) {
			// 리포지토리 빈 부재(슬라이스 테스트 등) — 사칭을 적용할 수 없으니 안전하게 통과시킨다.
			log.warn("UserRepository 빈 부재로 act-as 미적용 — targetUserId={}, path={}", targetUserId, path);
			chain.doFilter(request, response);
			return;
		}

		AppUser targetUser = userRepository.findById(targetUserId).orElse(null);
		if (targetUser == null) {
			writeJson(response, HttpStatus.NOT_FOUND, NOT_FOUND_BODY);
			return;
		}

		long adminId = ((AppUserDetails) authentication.getPrincipal()).getUserId();
		AdminAuditLogRepository auditLogRepository = auditLogRepositoryProvider.getIfAvailable();
		if (auditLogRepository == null) {
			log.error("AdminAuditLogRepository 빈 부재로 감사 기록 생략 — adminId={}, targetUserId={}, path={}",
					adminId, targetUserId, path);
		} else {
			try {
				auditLogRepository.insert(adminId, targetUserId, path);
			} catch (RuntimeException e) {
				// 감사 기록 실패로 어드민의 정상 조회 자체를 막지 않는다 — 대신 크게 남긴다.
				log.error("어드민 감사 로그 기록 실패 — adminId={}, targetUserId={}, path={}", adminId, targetUserId, path, e);
			}
		}

		AppUserDetails targetDetails = new AppUserDetails(targetUser);
		Authentication swapped = UsernamePasswordAuthenticationToken.authenticated(
				targetDetails, null, targetDetails.getAuthorities());
		SecurityContext swappedContext = SecurityContextHolder.createEmptyContext();
		swappedContext.setAuthentication(swapped);
		SecurityContextHolder.setContext(swappedContext);
		try {
			chain.doFilter(request, response);
		} finally {
			// 요청 스코프 한정 — 다음 필터(SecurityContextHolderFilter 등)가 세션에 저장하기 전에
			// 반드시 어드민 본인 컨텍스트로 되돌린다.
			SecurityContextHolder.setContext(originalContext);
		}
	}

	private static boolean isAdmin(Authentication authentication) {
		if (authentication == null || !authentication.isAuthenticated()
				|| !(authentication.getPrincipal() instanceof AppUserDetails)) {
			return false;
		}
		for (GrantedAuthority authority : authentication.getAuthorities()) {
			if ("ROLE_ADMIN".equals(authority.getAuthority())) {
				return true;
			}
		}
		return false;
	}

	private static String sessionUserIdOrAnonymous(Authentication authentication) {
		if (authentication != null && authentication.getPrincipal() instanceof AppUserDetails details) {
			return String.valueOf(details.getUserId());
		}
		return "anonymous";
	}

	private static void writeJson(HttpServletResponse response, HttpStatus status, String body) throws IOException {
		response.setStatus(status.value());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());
		response.getWriter().write(body);
	}
}
