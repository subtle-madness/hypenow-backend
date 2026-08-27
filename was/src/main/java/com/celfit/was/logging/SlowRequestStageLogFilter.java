package com.celfit.was.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Comparator;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 느린 요청 단계 분해 로그(2026-08-27) — 총 소요가 임계(1초) 이상인 요청에 한해, 요청이 지나간
 * 리포지토리 단계({@link RepositoryTimingAspect} 누적)를 단계당 1줄씩 구조화 로그로 남긴다. 요청
 * 추적 대시보드의 "단계 분해" 패널이 request_id로 이 줄들을 파싱해 워터폴을 그린다 —
 * {@code stage=}·{@code ms=} 키 이름과 "stage 값에 공백 없음"이 그 패널 정규식과의 계약이다.
 *
 * <p>{@link RequestIdFilter}(HIGHEST_PRECEDENCE) 바로 다음 순번 — MDC requestId가 이미 실려 있어
 * 여기 로그도 상관 슬롯에 같은 ID로 찍힌다. 평시(1초 미만)는 로그 0줄.
 *
 * <p>단계 합계와 총시간의 차("기타·조립·직렬화" 합성 단계)는 리포지토리 밖 앱 CPU — 조립·JSON
 * 직렬화·gzip이 대부분이다. 단, 이 필터는 서블릿 체인 안쪽이라 응답 스트리밍 완료까지를 포함하고
 * 전송 지연은 엣지(caddy) duration과의 차로 본다. 스트리밍 직렬화 중 예외 등으로 리포지토리 합계가
 * 총시간을 넘는 이론적 경우는 0으로 클램프한다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class SlowRequestStageLogFilter extends OncePerRequestFilter {

	private static final Logger log = LoggerFactory.getLogger(SlowRequestStageLogFilter.class);

	/**
	 * 단계 분해 로그 임계(ms) — 정상 요청은 수백 ms라 1초면 평시 소음 없이 문제 요청만 잡는다
	 * (08-25 posts 지연 6.4~17초 실측 대비 충분히 낮음).
	 */
	static final long THRESHOLD_MS = 1_000;
	/** 요청 하나가 남기는 단계 줄 상한 — 폭주 방어(정상 요청은 단계 수가 십수 개를 넘지 않는다). */
	private static final int MAX_STAGE_LINES = 20;
	/** 합성 단계 이름 — 리포지토리 밖 앱 시간(조립·직렬화·gzip 등). 공백 금지(패널 정규식 계약). */
	private static final String OTHER_STAGE = "기타(조립·직렬화·응답쓰기)";

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		long start = System.nanoTime();
		RequestStageTimings.begin();
		try {
			chain.doFilter(request, response);
		} finally {
			Map<String, long[]> stages = RequestStageTimings.end();
			long totalMs = (System.nanoTime() - start) / 1_000_000;
			if (totalMs >= THRESHOLD_MS) {
				logBreakdown(request, response, stages, totalMs);
			}
		}
	}

	private static void logBreakdown(HttpServletRequest request, HttpServletResponse response,
			Map<String, long[]> stages, long totalMs) {
		long repoMs = 0;
		for (long[] agg : stages.values()) {
			repoMs += agg[0] / 1_000_000;
		}
		stages.entrySet().stream()
				.sorted(Comparator.comparingLong((Map.Entry<String, long[]> e) -> e.getValue()[0]).reversed())
				.limit(MAX_STAGE_LINES)
				.forEach(e -> log.info("요청 단계 stage={} ms={} calls={}", e.getKey(),
						e.getValue()[0] / 1_000_000, e.getValue()[1]));
		log.info("요청 단계 stage={} ms={} calls=1", OTHER_STAGE, Math.max(0, totalMs - repoMs));
		log.info("요청 단계 요약 method={} uri={} status={} total_ms={} repo_ms={} stage_count={}",
				request.getMethod(), request.getRequestURI(), response.getStatus(), totalMs, repoMs, stages.size());
	}
}
