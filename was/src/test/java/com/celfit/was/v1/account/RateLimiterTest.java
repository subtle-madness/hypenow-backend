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

	// 분 경계 레이스 재현 — 과거 분(M-1) 스냅샷을 든 스레드의 늦은 스윕이 현재 분(M) 카운터를 지우면 안 된다
	@Test
	void 과거_분_스윕_유입에도_현재_분_윈도우와_카운터가_보존된다() {
		SteppingClock clock = new SteppingClock();
		RateLimiter limiter = new RateLimiter(clock, 1);

		assertThat(limiter.tryAcquire("k")).isTrue(); // 현재 분 윈도우 생성 + 상한 도달

		long staleMinute = clock.instant().getEpochSecond() / 60 - 1;
		limiter.sweepIfMinuteChanged(staleMinute); // M-1 스냅샷의 늦은 도착

		assertThat(limiter.size()).isEqualTo(1); // 현재 분 윈도우 잔존(삭제됐다면 0)
		// lastSweepMinute 역행·윈도우 삭제가 있었다면 카운터가 리셋돼 true가 됐을 것
		assertThat(limiter.tryAcquire("k")).isFalse();
	}

	@Test
	void 키가_다르면_카운터가_분리된다() {
		RateLimiter limiter = new RateLimiter(new SteppingClock(), 1);

		assertThat(limiter.tryAcquire("login:a@b.c|1.1.1.1")).isTrue();
		assertThat(limiter.tryAcquire("login:a@b.c|2.2.2.2")).isTrue();
		assertThat(limiter.tryAcquire("login:a@b.c|1.1.1.1")).isFalse();
	}

	@Test
	void 키별_상한_오버라이드_초과시_거부() {
		RateLimiter limiter = new RateLimiter(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), 10);
		assertThat(limiter.tryAcquire("send:a@b.c", 1)).isTrue();
		assertThat(limiter.tryAcquire("send:a@b.c", 1)).isFalse();
		// 다른 키는 독립
		assertThat(limiter.tryAcquire("send:x@y.z", 1)).isTrue();
	}
}
