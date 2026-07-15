package com.celfit.was.config;

import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.function.Supplier;
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

	@Bean
	public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
		http
				.csrf(csrf -> csrf
						.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
						.csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler()))
				.cors(cors -> cors.configurationSource(corsConfigurationSource()))
				.authorizeHttpRequests(auth -> auth
						.requestMatchers("/api/me", "/api/saved/**").authenticated()
						.anyRequest().permitAll())
				.exceptionHandling(ex -> ex.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
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
			csrfToken.get(); // 지연 발급 해제 — 쿠키가 없으면 이 시점에 XSRF-TOKEN이 내려간다
		}

		@Override
		public String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken csrfToken) {
			String headerValue = request.getHeader(csrfToken.getHeaderName());
			return (StringUtils.hasText(headerValue) ? plain : xor).resolveCsrfTokenValue(request, csrfToken);
		}
	}

	@Bean
	public CorsConfigurationSource corsConfigurationSource() {
		CorsConfiguration configuration = new CorsConfiguration();
		configuration.setAllowedOrigins(Arrays.asList(allowedOrigins));
		configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE"));
		configuration.setAllowedHeaders(List.of("*"));
		configuration.setAllowCredentials(true);
		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		// 구 /api·신 /v1 두 표면 모두 프론트 오리진 허용 (같은 configuration 재사용)
		source.registerCorsConfiguration("/api/**", configuration);
		source.registerCorsConfiguration("/v1/**", configuration);
		return source;
	}
}
