package com.celfit.was.admin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * CODES_API_KEY Bearer 검증 필터(설계 2026-07-20) — /admin/signup-codes 전용 @Order(0) 체인에서만 동작.
 * 키 미설정이면 503(fail-closed·오설정 구분), 토큰 일치 시 인증 세팅(권한 무관). 불일치·헤더 없음이면
 * 인증 미세팅 → 체인의 authenticated()가 진입점 401로 처리. 비교는 MessageDigest.isEqual(상수시간).
 */
public class CodesApiKeyAuthFilter extends OncePerRequestFilter {

	private final String apiKey;

	public CodesApiKeyAuthFilter(String apiKey) {
		this.apiKey = apiKey;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		if (apiKey == null || apiKey.isBlank()) {
			writeError(response, 503, "CODES_API_KEY 미설정");
			return;
		}
		String header = request.getHeader("Authorization");
		if (header != null && header.startsWith("Bearer ")) {
			String token = header.substring("Bearer ".length());
			boolean ok = MessageDigest.isEqual(
					token.getBytes(StandardCharsets.UTF_8), apiKey.getBytes(StandardCharsets.UTF_8));
			if (ok) {
				SecurityContextHolder.getContext().setAuthentication(
						new UsernamePasswordAuthenticationToken("codes-api", null, List.of()));
			}
		}
		chain.doFilter(request, response);
	}

	private void writeError(HttpServletResponse response, int status, String message) throws IOException {
		response.setStatus(status);
		response.setContentType("application/json");
		response.setCharacterEncoding("UTF-8");
		response.getWriter().write("{\"error\":\"" + message + "\"}");
	}
}
