package com.celfit.monitoring.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 요청당 짧은 ID를 MDC(requestId)에 실어 로그를 요청 단위로 묶는다(2026-08-23 대시보드 진단
 * 설계 후속 — APM 없는 개별 요청 추적). 출력 위치는 logging.pattern.correlation(application.yml)이
 * 담당한다.
 *
 * <p>was가 내부 호출에 X-Request-Id를 실어 보내면 그대로 이어받아 두 모듈 로그가 같은 ID로
 * 묶인다. 헤더는 외부 입력이므로 형식(영숫자·하이픈·언더스코어 1~64자)을 벗어나면 무시하고 새로
 * 생성한다 — 로그 라인에 그대로 찍히는 값이라 개행 주입 등으로 라인 구조(Alloy 파싱)를 깨면
 * 안 된다. 응답 헤더에도 되돌려 실어 호출자(수동 진단 포함)가 해당 요청의 로그를 찾을 수 있게 한다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

	public static final String HEADER = "X-Request-Id";
	public static final String MDC_KEY = "requestId";

	private static final Pattern VALID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
	private static final char[] ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789".toCharArray();
	private static final int GENERATED_LENGTH = 8;

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String incoming = request.getHeader(HEADER);
		String requestId = incoming != null && VALID.matcher(incoming).matches() ? incoming : generate();
		MDC.put(MDC_KEY, requestId);
		response.setHeader(HEADER, requestId);
		try {
			chain.doFilter(request, response);
		} finally {
			MDC.remove(MDC_KEY);
		}
	}

	private static String generate() {
		char[] chars = new char[GENERATED_LENGTH];
		ThreadLocalRandom random = ThreadLocalRandom.current();
		for (int i = 0; i < chars.length; i++) {
			chars[i] = ALPHABET[random.nextInt(ALPHABET.length)];
		}
		return new String(chars);
	}
}
