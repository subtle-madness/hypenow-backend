package com.celfit.instagram.source;

import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * 경로별(예: path+errorClass) WARN 속도제한 — 반복 실패로 로그가 도배되는 것을 막는다. 기본
 * 30초에 1건만 실제로 출력해야 한다고 판정하고, 그 사이 억제된 건수는 다음 출력 때 함께 돌려줘
 * 호출부가 "(억제 N건)"처럼 로그에 합산해 남기게 한다. 판정만 하는 순수 로직이라(로깅 자체는
 * 호출부 책임) 로그 캡처 없이 단위 테스트가 쉽다. 스레드 안전(synchronized — 호출 빈도가 낮아
 * 락 경합 걱정 없음).
 */
final class RateLimitedWarn {

	private static final long DEFAULT_INTERVAL_MILLIS = 30_000L;

	private final long intervalMillis;
	private final LongSupplier clock;
	private final Map<String, Long> lastEmittedAt = new HashMap<>();
	private final Map<String, Long> suppressedCount = new HashMap<>();

	RateLimitedWarn() {
		this(DEFAULT_INTERVAL_MILLIS, System::currentTimeMillis);
	}

	RateLimitedWarn(long intervalMillis, LongSupplier clock) {
		this.intervalMillis = intervalMillis;
		this.clock = clock;
	}

	/**
	 * 이번 호출을 실제로 출력해야 하면 그 사이 억제된 건수(0 이상)를 담아 반환하고, 아직 간격이 안
	 * 지났으면 억제 건수를 1 늘리고 -1을 반환한다(호출부는 -1이면 로그를 찍지 않는다).
	 */
	synchronized long shouldEmit(String key) {
		long now = clock.getAsLong();
		Long last = lastEmittedAt.get(key);
		if (last != null && now - last < intervalMillis) {
			suppressedCount.merge(key, 1L, Long::sum);
			return -1L;
		}
		lastEmittedAt.put(key, now);
		Long suppressed = suppressedCount.remove(key);
		return suppressed == null ? 0L : suppressed;
	}
}
