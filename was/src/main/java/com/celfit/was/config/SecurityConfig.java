package com.celfit.was.config;

import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.CsrfTokenRequestHandler;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;
import org.springframework.util.StringUtils;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * 세션 쿠키 인증 — DaoAuthenticationProvider(AppUserDetailsService + BCrypt)는
 * Boot가 UserDetailsService·PasswordEncoder 빈으로부터 자동 구성한다
 * (AuthenticationConfiguration#getAuthenticationManager — WebSecurityConfigurerAdapter 폐지 이후 표준 관용구,
 *  @WebMvcTest 슬라이스에 AppUserDetailsService 빈이 없어도 컨텍스트가 뜬다).
 * CSRF는 쿠키 토큰(SPA가 XSRF-TOKEN 쿠키 → X-XSRF-TOKEN 헤더로 되돌려 보낸다), 미인증은 401(리다이렉트 금지).
 * CORS는 기존 WebConfig의 GET-only 매핑을 걷어내고 이 CorsConfigurationSource로 일원화한다 —
 * 세션 쿠키가 크로스 오리진으로 오가야 해서 allowCredentials(true) + 쓰기 메서드 허용이 필요하다.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

	private final String[] allowedOrigins;

	public SecurityConfig(@Value("${was.cors.allowed-origins}") String[] allowedOrigins) {
		this.allowedOrigins = allowedOrigins;
	}

	@Bean
	public PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}

	@Bean
	public AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
		return configuration.getAuthenticationManager();
	}

	/**
	 * 스웨거 전용 체인(설계 2026-07-19) — ADMIN만 문서 열람. HTTP Basic 팝업으로 받고
	 * 기존 DaoAuthenticationProvider(users 테이블 + BCrypt)를 그대로 탄다.
	 * STATELESS는 보안 불변식: 세션엔 로그인 시점 권한 스냅샷(토큰 authorities)이 남아
	 * 세션을 읽으면 강등된 admin이 로그아웃 전까지 통과한다 — 매 요청 Basic 재인증으로
	 * 현재 DB role을 읽는다. CSRF는 GET 전용 문서 표면이라 불필요.
	 * 매처는 springdoc 기본 경로와 결합돼 있다(/swagger-ui.html 진입점·/v3/api-docs.yaml 포함) —
	 * springdoc.api-docs.path·swagger-ui.path를 바꾸면 여기도 같이 바꿀 것.
	 */
	@Bean
	@Order(1)
	public SecurityFilterChain swaggerFilterChain(HttpSecurity http) throws Exception {
		http
				.securityMatcher("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**", "/v3/api-docs.yaml")
				.authorizeHttpRequests(auth -> auth.anyRequest().hasRole("ADMIN"))
				.httpBasic(Customizer.withDefaults())
				.csrf(AbstractHttpConfigurer::disable)
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
		return http.build();
	}

	@Bean
	@Order(2)
	public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
		http
				.csrf(csrf -> csrf
						.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
						.csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler())
						// 게이트 이벤트만 CSRF 면제 — 익명 첫 방문자는 XSRF-TOKEN 쿠키를 미리 받을 방법이
						// 없어(첫 요청이 곧 이벤트) 면제 없이는 스펙 6.19의 "익명 기록"이 유실된다.
						// append-only 측정 로그라 CSRF 표적 가치가 없고(위조돼도 남의 데이터 변조 불가),
						// fire-and-forget이라 안전하다.
						.ignoringRequestMatchers("/v1/events/gate"))
				.cors(cors -> cors.configurationSource(corsConfigurationSource()))
				.authorizeHttpRequests(auth -> auth
						// 로그인 월(07-17 설계) — 기본 잠금, 열린 경로만 나열(화이트리스트).
						// 새 엔드포인트는 기본이 잠김이라 실수로 새지 않는다.
						.requestMatchers("/v1/auth/**").permitAll()      // 인증 입구(레거시 /api/auth는 잠금 — /v1 일원화)
						.requestMatchers("/v1/events/gate").permitAll()  // 익명 게이트 측정 유지(스펙 6.19)
						.requestMatchers("/v1/stats").permitAll()        // 랜딩 통계(스펙 6.20) — 로그인 전 랜딩 페이지가 소비, 공개 캐시 전제
						.requestMatchers("/health").permitAll()          // 배포 헬스체크(익명 curl)
						.anyRequest().authenticated())
				.exceptionHandling(ex -> ex.authenticationEntryPoint(new V1AwareAuthenticationEntryPoint()))
				.formLogin(AbstractHttpConfigurer::disable)
				.httpBasic(AbstractHttpConfigurer::disable)
				.logout(AbstractHttpConfigurer::disable);
		return http.build();
	}

	/**
	 * SPA용 CSRF 핸들러 (Spring Security 문서 관용구) — 기본 Xor 핸들러만 쓰면 두 가지가 막힌다:
	 * ① 토큰이 지연 발급이라 첫 쓰기 요청 전에 XSRF-TOKEN 쿠키를 받을 방법이 없고(첫 요청이 무조건 403),
	 * ② SPA는 쿠키의 raw 값을 X-XSRF-TOKEN 헤더로 되돌려 보내는데 Xor 핸들러는 XOR 인코딩 값만 받는다.
	 * 그래서 handle()에서 csrfToken.get()으로 매 요청 쿠키 저장을 강제하고(BREACH 방어는 어차피
	 * 요청 본문에 토큰을 안 싣는 SPA 헤더 방식엔 해당 없음), 헤더로 온 값은 plain 핸들러로 해석한다.
	 * 실 curl E2E에서 발견 — MockMvc csrf() 후처리기는 쿠키 왕복을 타지 않아 못 잡는 갭.
	 */
	static final class SpaCsrfTokenRequestHandler implements CsrfTokenRequestHandler {

		private final CsrfTokenRequestHandler plain = new CsrfTokenRequestAttributeHandler();
		private final CsrfTokenRequestHandler xor = new XorCsrfTokenRequestAttributeHandler();

		@Override
		public void handle(HttpServletRequest request, HttpServletResponse response, Supplier<CsrfToken> csrfToken) {
			xor.handle(request, response, csrfToken);
			var unused = csrfToken.get(); // 지연 발급 해제 — 쿠키가 없으면 이 시점에 XSRF-TOKEN이 내려간다 (반환값은 불필요)
		}

		@Override
		public String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken csrfToken) {
			String headerValue = request.getHeader(csrfToken.getHeaderName());
			return (StringUtils.hasText(headerValue) ? plain : xor).resolveCsrfTokenValue(request, csrfToken);
		}
	}

	/**
	 * 미인증 401 진입점 — /v1 경로는 스펙 3.1 envelope JSON을 직접 쓰고(UNAUTHORIZED "로그인이 필요합니다."),
	 * 그 외(/api 등 구 표면)는 기존 HttpStatusEntryPoint 동작(빈 401 본문)을 유지한다.
	 * advice(V1ExceptionAdvice)는 컨트롤러 도달 후에만 작동해 필터 단계 401은 여기서 직접 써야 한다.
	 */
	static final class V1AwareAuthenticationEntryPoint implements AuthenticationEntryPoint {

		private static final String V1_UNAUTHORIZED_BODY =
				"{\"success\":false,\"data\":null,\"error\":{\"code\":\"UNAUTHORIZED\",\"message\":\"로그인이 필요합니다.\"}}";

		private final AuthenticationEntryPoint fallback = new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED);

		@Override
		public void commence(HttpServletRequest request, HttpServletResponse response,
				AuthenticationException authException) throws IOException, ServletException {
			if (request.getRequestURI().startsWith("/v1/")) {
				response.setStatus(HttpStatus.UNAUTHORIZED.value());
				response.setContentType(MediaType.APPLICATION_JSON_VALUE);
				response.setCharacterEncoding(StandardCharsets.UTF_8.name());
				response.getWriter().write(V1_UNAUTHORIZED_BODY);
			} else {
				fallback.commence(request, response, authException);
			}
		}
	}

	@Bean
	public CorsConfigurationSource corsConfigurationSource() {
		CorsConfiguration configuration = new CorsConfiguration();
		configuration.setAllowedOrigins(Arrays.asList(allowedOrigins));
		configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE")); // PATCH: /v1/me(스펙 6.13)
		configuration.setAllowedHeaders(List.of("*"));
		configuration.setAllowCredentials(true);
		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		// 구 /api·신 /v1 두 표면 모두 프론트 오리진 허용 (같은 configuration 재사용)
		source.registerCorsConfiguration("/api/**", configuration);
		source.registerCorsConfiguration("/v1/**", configuration);
		return source;
	}
}
