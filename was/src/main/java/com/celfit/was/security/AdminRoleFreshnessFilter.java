package com.celfit.was.security;

import com.celfit.was.auth.AppUserDetails;
import com.celfit.was.auth.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * /v1/admin/** 세션 authority 신선도 재확인(어드민 백엔드 API 설계 2026-08-01 §1, 세션 스냅샷 재확인
 * 결정) — @Order(2) 체인의 {@code AuthorizationFilter} 뒤에 등록한다. 이 지점에 도달했다는 것 자체가
 * 세션 authority 기준 {@code hasRole("ADMIN")} 판정을 이미 통과했다는 뜻이지만, 그 authority는
 * 로그인 시점 스냅샷이라(AppUserDetails 클래스 주석 참고) 세션만으로는 강등을 반영하지 못한다.
 * 그래서 /v1/admin/** 요청마다 {@link UserRepository}로 DB의 현재 role을 다시 읽어, ADMIN이
 * 아니면 403 FORBIDDEN envelope로 막는다. ActAsUserFilter의 isAdmin 재확인과 짝을 이루는 두 번째
 * 게이트다.
 *
 * <p>어드민 조회 API는 운영팀 내부 도구라 트래픽이 낮다 — 요청당 role 컬럼 하나만 읽는 경량 SELECT
 * (findRoleById)를 추가하는 비용은 캐시 없이도 수용 가능하다고 판단했다(요청 빈도가 낮아 부담이 적음).
 *
 * <p>의존성은 {@link ObjectProvider}로 받는다(ActAsUserFilter·LastActiveAtFilter와 동일 이유 —
 * @WebMvcTest 슬라이스 대부분엔 UserRepository 빈이 없다). <b>빈 부재로 재확인을 건너뛰는 것이
 * 게이트 무력화(fail-open)가 되지는 않는다</b> — 이 필터에 도달한 요청은 이미 세션 authority
 * 기준 hasRole("ADMIN") 검사를 통과한 상태이므로, 재확인만 생략될 뿐 인가 자체가 뚫리는 것은
 * 아니다(신선도만 못 얻을 뿐).
 */
public class AdminRoleFreshnessFilter extends OncePerRequestFilter {

	private static final Logger log = LoggerFactory.getLogger(AdminRoleFreshnessFilter.class);

	private static final String ADMIN_PATH_PREFIX = "/v1/admin/";

	private static final String FORBIDDEN_BODY =
			"{\"success\":false,\"data\":null,\"error\":{\"code\":\"FORBIDDEN\",\"message\":\"권한이 없습니다.\"}}";

	private final ObjectProvider<UserRepository> userRepositoryProvider;

	public AdminRoleFreshnessFilter(ObjectProvider<UserRepository> userRepositoryProvider) {
		this.userRepositoryProvider = userRepositoryProvider;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		if (!request.getRequestURI().startsWith(ADMIN_PATH_PREFIX)) {
			chain.doFilter(request, response);
			return;
		}

		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (!(authentication != null && authentication.getPrincipal() instanceof AppUserDetails details)) {
			// 이 지점 도달 자체가 세션 hasRole(ADMIN) 통과를 의미해 이례적이나, principal 형태가
			// 예상과 다르면 재확인을 건너뛰고 기존 세션 기준 인가 판정에 맡긴다.
			chain.doFilter(request, response);
			return;
		}

		UserRepository userRepository = userRepositoryProvider.getIfAvailable();
		if (userRepository == null) {
			// @WebMvcTest 슬라이스 등 빈 부재 — 위 클래스 주석대로 fail-open이 아니다: 세션 hasRole(ADMIN)
			// 검사는 이미 통과했고, 여기서는 그 위에 얹는 DB 신선도 재확인만 생략된다.
			log.warn("UserRepository 빈 부재로 /v1/admin 세션 신선도 재확인 생략 — userId={}, path={}",
					details.getUserId(), request.getRequestURI());
			chain.doFilter(request, response);
			return;
		}

		boolean stillAdmin = userRepository.findRoleById(details.getUserId())
				.filter("ADMIN"::equals)
				.isPresent();
		if (!stillAdmin) {
			log.warn("세션 authority는 ADMIN이나 DB role 강등 확인 — /v1/admin 접근 차단, userId={}, path={}",
					details.getUserId(), request.getRequestURI());
			response.setStatus(HttpStatus.FORBIDDEN.value());
			response.setContentType(MediaType.APPLICATION_JSON_VALUE);
			response.setCharacterEncoding(StandardCharsets.UTF_8.name());
			response.getWriter().write(FORBIDDEN_BODY);
			return;
		}

		chain.doFilter(request, response);
	}
}
