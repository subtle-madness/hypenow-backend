package com.celfit.was.security;

import com.celfit.was.auth.AppUserDetails;
import com.celfit.was.auth.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * app.users.last_active_at 갱신(어드민 백엔드 API 설계 2026-08-01 §3, A3) — 인증된 요청마다
 * 인메모리 5분 스로틀로 UPDATE한다(요청당 UPDATE는 스팸이라 계약이 금지). 실패는 관측 부가
 * 기능이 본 요청을 죽이면 안 되므로 WARN 로그만 남긴다.
 *
 * <p>@Order(2) 체인에서 {@link ActAsUserFilter}보다 앞에 등록해야 한다 — act-as 중에는 원 principal
 * (사칭한 어드민 본인)이 이미 SecurityContext에서 대상 유저로 교체된 뒤라, 이 필터가 뒤에 있으면
 * "대상 유저의 활동"으로 잘못 기록된다. 원본 요청은 항상 실 로그인 주체 기준이어야 한다.
 *
 * <p>의존성은 {@link ObjectProvider}로 받는다 — SecurityConfig가 @WebMvcTest 슬라이스마다 두루
 * @Import되는데(기존 관용구), 슬라이스 대부분은 UserRepository·Clock 빈이 없다. 즉시 주입이면
 * 그 슬라이스들의 컨텍스트 기동 자체가 깨진다 — 빈 부재는 조용히 스킵(관측 부가 기능 원칙)한다.
 */
public class LastActiveAtFilter extends OncePerRequestFilter {

	private static final Logger log = LoggerFactory.getLogger(LastActiveAtFilter.class);

	private static final Duration THROTTLE = Duration.ofMinutes(5);

	private final ObjectProvider<UserRepository> userRepositoryProvider;
	private final ObjectProvider<Clock> clockProvider;
	private final ConcurrentHashMap<Long, Instant> lastUpdatedAt = new ConcurrentHashMap<>();

	public LastActiveAtFilter(ObjectProvider<UserRepository> userRepositoryProvider,
			ObjectProvider<Clock> clockProvider) {
		this.userRepositoryProvider = userRepositoryProvider;
		this.clockProvider = clockProvider;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication != null && authentication.isAuthenticated()
				&& authentication.getPrincipal() instanceof AppUserDetails principal) {
			touchIfDue(principal.getUserId());
		}
		chain.doFilter(request, response);
	}

	private void touchIfDue(long userId) {
		UserRepository userRepository = userRepositoryProvider.getIfAvailable();
		if (userRepository == null) {
			return;
		}
		Instant now = clockProvider.getIfAvailable(Clock::systemUTC).instant();
		Instant last = lastUpdatedAt.get(userId);
		if (last != null && Duration.between(last, now).compareTo(THROTTLE) < 0) {
			return;
		}
		// 동시 요청 경합으로 스로틀 창 안에서 두 번 갱신될 수 있으나(스팸 방지가 목적이라 엄밀한
		// 배타 제어는 불필요) put을 먼저 해 이후 요청이 즉시 스킵되게 한다.
		lastUpdatedAt.put(userId, now);
		try {
			userRepository.updateLastActiveAt(userId);
		} catch (RuntimeException e) {
			log.warn("last_active_at 갱신 실패 — userId={}", userId, e);
		}
	}
}
