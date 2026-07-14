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
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
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
				.csrf(csrf -> csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse()))
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

	@Bean
	public CorsConfigurationSource corsConfigurationSource() {
		CorsConfiguration configuration = new CorsConfiguration();
		configuration.setAllowedOrigins(Arrays.asList(allowedOrigins));
		configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE"));
		configuration.setAllowedHeaders(List.of("*"));
		configuration.setAllowCredentials(true);
		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/api/**", configuration);
		return source;
	}
}
