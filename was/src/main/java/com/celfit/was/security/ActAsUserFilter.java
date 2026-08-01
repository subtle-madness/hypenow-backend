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
 *
 * <p><b>세션 authority는 신선도가 보장되지 않는다.</b> 세션엔 로그인 시점 authorities가 그대로
 * 영속돼(AppUserDetails 클래스 주석 참고), DB에서 role을 USER로 강등해도 로그아웃 전까지 세션은
 * ROLE_ADMIN을 계속 주장한다. 그래서 {@link #isAdmin}은 세션 authority만 보지 않고 매 요청
 * {@link UserRepository}로 현재 DB role을 재조회해 실제로 ADMIN일 때만 act-as를 허용한다
 * (어드민 백엔드 API 설계 §1·§2, 세션 스냅샷 재확인 결정).
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
		UserRepository userRepository = userRepositoryProvider.getIfAvailable();
		if (!isAdmin(authentication, userRepository)) {
			// 운영 도메인 프록시 우회 등 비어드민(익명 포함)의 사칭 시도, 또는 세션은 ADMIN이나 DB role이
			// 강등된 경우(신선도 재확인 실패) — 헤더는 무시하고 통과, 신호만 남긴다.
			log.warn("어드민이 아닌 세션(혹은 DB role 강등)의 X-Act-As-User 시도 — sessionUserId={}, targetUserId={}, path={}",
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
		long targetUserId;
		try {
			targetUserId = Long.parseLong(targetIdHeader.trim());
		} catch (NumberFormatException e) {
			// NUMERIC_ID(^[0-9]+$)는 자릿수 무제한이라 Long.MAX_VALUE 초과 숫자 문자열도 통과시킨다 —
			// 그 경우 parseLong이 NFE를 던지므로 AdminUsersController.parseId와 동일하게 404로 흡수한다
			// (코드 리뷰 지적 — 방어 없으면 필터 밖으로 NFE가 전파돼 envelope 깨진 기본 에러 응답이 나간다).
			writeJson(response, HttpStatus.NOT_FOUND, NOT_FOUND_BODY);
			return;
		}

		// userRepository는 이 지점에서 항상 non-null이다 — isAdmin()이 이미 DB role 재확인에
		// 성공했어야만(위에서 return하지 않고) 여기 도달하고, 그 재확인 자체가 userRepository != null을
		// 전제한다(userRepository가 null이면 isAdmin은 재확인 불가로 false를 반환한다).
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

	/**
	 * 세션 authority(ROLE_ADMIN)만으로는 불충분하다 — 로그인 시점 스냅샷이라 강등돼도 로그아웃 전까지
	 * 남는다(클래스 주석 참고). 세션이 ADMIN을 주장해도 userRepository로 현재 DB role을 재조회해
	 * 실제로 ADMIN일 때만 true다. userRepository가 없으면(슬라이스 테스트 등) 재확인이 불가능하므로
	 * 안전하게 비어드민으로 취급한다.
	 */
	private static boolean isAdmin(Authentication authentication, UserRepository userRepository) {
		if (authentication == null || !authentication.isAuthenticated()
				|| !(authentication.getPrincipal() instanceof AppUserDetails details)) {
			return false;
		}
		boolean sessionClaimsAdmin = false;
		for (GrantedAuthority authority : authentication.getAuthorities()) {
			if ("ROLE_ADMIN".equals(authority.getAuthority())) {
				sessionClaimsAdmin = true;
				break;
			}
		}
		if (!sessionClaimsAdmin || userRepository == null) {
			return false;
		}
		return userRepository.findRoleById(details.getUserId())
				.filter("ADMIN"::equals)
				.isPresent();
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
