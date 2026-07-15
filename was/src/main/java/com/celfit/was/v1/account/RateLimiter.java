package com.celfit.was.v1.account;

import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 로그인·가입·이벤트 레이트리밋 — 키(예: "login:email|ip")당 분당 상한. 초과 시 429 RATE_LIMITED. */
@Component
public class RateLimiter {

	private record Window(long epochMinute, AtomicInteger count) {
	}

	private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
	private final AtomicLong lastSweepMinute = new AtomicLong(Long.MIN_VALUE);
	private final Clock clock;
	private final int perMinute;

	public RateLimiter(Clock clock, @Value("${was.rate-limit.per-minute:10}") int perMinute) {
		this.clock = clock;
		this.perMinute = perMinute;
	}

	/** 허용되면 true. 분이 바뀌면 카운터 리셋(고정 윈도우). */
	public boolean tryAcquire(String key) {
		long minute = clock.instant().getEpochSecond() / 60;
		sweepIfMinuteChanged(minute);
		Window w = windows.compute(key, (k, old) ->
				(old == null || old.epochMinute() != minute) ? new Window(minute, new AtomicInteger()) : old);
		return w.count().incrementAndGet() <= perMinute;
	}

	/**
	 * 분당 1회 스윕 — 활성 키만 잔존. 키에 공격자 제어 입력(임의 이메일)이 들어가므로 만료 윈도우를
	 * 청소하지 않으면 맵이 무한 성장한다. compareAndSet으로 분이 바뀐 첫 호출자 1명만 청소를 수행.
	 *
	 * 가드는 단조 증가(minute > last)만 허용 — 분 경계에서 과거 분 스냅샷을 든 스레드가 늦게 도착해도
	 * lastSweepMinute이 역행해 현재 분 윈도우를 지우는(카운터 리셋) 레이스가 없다. removeIf도
	 * != 대신 < 비교(이중 방어) — 스테일 minute이 어떤 경로로 유입돼도 최신 윈도우는 보존된다.
	 * (테스트가 과거 분 유입을 직접 재현할 수 있게 패키지 프라이빗.)
	 */
	void sweepIfMinuteChanged(long minute) {
		long last = lastSweepMinute.get();
		if (minute > last && lastSweepMinute.compareAndSet(last, minute)) {
			windows.entrySet().removeIf(e -> e.getValue().epochMinute() < minute);
		}
	}

	/** 테스트 전용 — 스윕 후 잔존 윈도우 수 관측. */
	int size() {
		return windows.size();
	}
}
