package com.celfit.instagram.source.self;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 회복가능 자체 실패를 재시도한다 — K=1(요청당 새 exit IP)이라 재시도가 곧 IP 교체다(crawler
 * BLOCK_MAX_ATTEMPTS=3 계승). 401(익명 한도)·전송 실패·429는 재시도로 회복(다음 IP는 예산이
 * 남았을 확률), NOT_FOUND·구조적 400·로그인 벽은 재시도 무의미라 즉시 전파. 재시도를 소진하면
 * 마지막 예외를 던져 FailoverInstagramSource가 Hiker로 폴백하게 한다.
 *
 * <p>총 시간 예산(budget, 기본 8초)도 함께 건다 — maxAttempts(3) × request-timeout(15s)이면 self
 * 최악 45초가 모든 경로(사용자 대면 동기 등록 포함)에 동일 적용돼, 동기 등록(was→monitoring read
 * timeout 10s)에서는 self가 트러블일 때 폴백이 도착하기 전에 사용자가 503을 본다(F3). 재시도
 * 판단 시점에 경과 시간이 예산을 넘었으면 남은 재시도를 포기하고 즉시 마지막 예외를 전파해
 * FailoverInstagramSource가 Hiker로 폴백하게 한다 — 진행 중인 요청 자체를 중단시키지는 않는다.
 * p95 정상 경로(wpi 5.3s 실측)는 8초 예산 안에서 1회 시도로 끝난다.
 */
public final class SelfRetry {

	private static final Logger log = LoggerFactory.getLogger(SelfRetry.class);
	private static final Duration DEFAULT_BUDGET = Duration.ofSeconds(8);

	private final int maxAttempts;
	private final Duration budget;
	private final Clock clock;

	public SelfRetry(int maxAttempts) {
		this(maxAttempts, DEFAULT_BUDGET);
	}

	public SelfRetry(int maxAttempts, Duration budget) {
		this(maxAttempts, budget, Clock.systemUTC());
	}

	/** 테스트 전용 — clock을 결정적으로 제어한다. */
	SelfRetry(int maxAttempts, Duration budget, Clock clock) {
		this.maxAttempts = Math.max(1, maxAttempts);
		this.budget = budget;
		this.clock = clock;
	}

	public <T> T call(String surface, Supplier<T> op) {
		Instant deadline = clock.instant().plus(budget);
		SelfCrawlException last = null;
		for (int attempt = 1; attempt <= maxAttempts; attempt++) {
			try {
				return op.get();
			} catch (SelfCrawlException e) {
				last = e;
				if (!recoverable(e.errorClass()) || attempt == maxAttempts) {
					throw e;
				}
				if (!clock.instant().isBefore(deadline)) {
					log.info("자체 {} 시간예산({}) 초과 — 남은 재시도 포기, 즉시 전파 (attempt={}/{}, {})",
							surface, budget, attempt, maxAttempts, e.errorClass());
					throw e;
				}
				log.info("자체 {} 재시도 {}/{} — {} (다음 시도=새 IP)",
						surface, attempt + 1, maxAttempts, e.errorClass());
			}
		}
		throw last;
	}

	private static boolean recoverable(SelfErrorClass ec) {
		return ec == SelfErrorClass.RECOVERABLE_401
				|| ec == SelfErrorClass.TRANSPORT
				|| ec == SelfErrorClass.RATE_LIMIT_429;
	}
}
