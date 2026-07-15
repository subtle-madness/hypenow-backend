package com.celfit.was.v1.account;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class RateLimiterTest {

	/** 테스트에서 시간을 임의로 진행시키는 Clock 스텁 — 고정 윈도우 리셋 검증용. */
	private static final class SteppingClock extends Clock {

		private Instant now = Instant.parse("2026-07-15T00:00:00Z");

		void advance(Duration duration) {
			now = now.plus(duration);
		}

		@Override
		public Instant instant() {
			return now;
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}
	}

	@Test
	void 상한_내_요청은_허용된다() {
		RateLimiter limiter = new RateLimiter(new SteppingClock(), 3);

		assertThat(limiter.tryAcquire("login:a@b.c|1.2.3.4")).isTrue();
		assertThat(limiter.tryAcquire("login:a@b.c|1.2.3.4")).isTrue();
		assertThat(limiter.tryAcquire("login:a@b.c|1.2.3.4")).isTrue();
	}

	@Test
	void 상한_초과는_거부된다() {
		RateLimiter limiter = new RateLimiter(new SteppingClock(), 2);

		limiter.tryAcquire("k");
		limiter.tryAcquire("k");

		assertThat(limiter.tryAcquire("k")).isFalse();
	}

	@Test
	void 분이_바뀌면_카운터가_리셋된다() {
		SteppingClock clock = new SteppingClock();
		RateLimiter limiter = new RateLimiter(clock, 1);

		assertThat(limiter.tryAcquire("k")).isTrue();
		assertThat(limiter.tryAcquire("k")).isFalse();

		clock.advance(Duration.ofMinutes(1));

		assertThat(limiter.tryAcquire("k")).isTrue();
	}

	@Test
	void 분이_지나면_만료_윈도우가_맵에서_청소된다() {
		SteppingClock clock = new SteppingClock();
		RateLimiter limiter = new RateLimiter(clock, 10);

		limiter.tryAcquire("login:old-1@x.com|1.1.1.1");
		limiter.tryAcquire("login:old-2@x.com|1.1.1.1");
		assertThat(limiter.size()).isEqualTo(2);

		clock.advance(Duration.ofMinutes(1));
		limiter.tryAcquire("login:fresh@x.com|1.1.1.1");

		// 분당 1회 스윕 — 이전 분 키 2개는 제거되고 활성 키만 잔존
		assertThat(limiter.size()).isEqualTo(1);
	}

	@Test
	void 키가_다르면_카운터가_분리된다() {
		RateLimiter limiter = new RateLimiter(new SteppingClock(), 1);

		assertThat(limiter.tryAcquire("login:a@b.c|1.1.1.1")).isTrue();
		assertThat(limiter.tryAcquire("login:a@b.c|2.2.2.2")).isTrue();
		assertThat(limiter.tryAcquire("login:a@b.c|1.1.1.1")).isFalse();
	}
}
