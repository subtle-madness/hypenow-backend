package com.celfit.was.v1.account;

import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 로그인·가입·이벤트 레이트리밋 — 키(예: "login:email|ip")당 분당 상한. 초과 시 429 RATE_LIMITED. */
@Component
public class RateLimiter {

	private record Window(long epochMinute, AtomicInteger count) {
	}

	private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
	private final Clock clock;
	private final int perMinute;

	public RateLimiter(Clock clock, @Value("${was.rate-limit.per-minute:10}") int perMinute) {
		this.clock = clock;
		this.perMinute = perMinute;
	}

	/** 허용되면 true. 분이 바뀌면 카운터 리셋(고정 윈도우). */
	public boolean tryAcquire(String key) {
		long minute = clock.instant().getEpochSecond() / 60;
		Window w = windows.compute(key, (k, old) ->
				(old == null || old.epochMinute() != minute) ? new Window(minute, new AtomicInteger()) : old);
		return w.count().incrementAndGet() <= perMinute;
	}
}
