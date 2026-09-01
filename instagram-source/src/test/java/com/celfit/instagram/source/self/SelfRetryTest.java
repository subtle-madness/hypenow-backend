package com.celfit.instagram.source.self;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SelfRetryTest {

	/** 테스트에서 시간을 임의로 진행시키는 Clock 스텁(RateLimiterTest SteppingClock과 동형). */
	private static final class SteppingClock extends Clock {

		private Instant now = Instant.parse("2026-09-01T00:00:00Z");

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
	void 회복가능_실패_2회_후_성공하면_결과를_돌려주고_3회_시도한다() {
		SelfRetry retry = new SelfRetry(3);
		AtomicInteger calls = new AtomicInteger();
		String r = retry.call("embed", () -> {
			int n = calls.incrementAndGet();
			if (n < 3) {
				throw new SelfCrawlException(SelfErrorClass.RECOVERABLE_401, "401");
			}
			return "ok";
		});
		assertThat(r).isEqualTo("ok");
		assertThat(calls.get()).isEqualTo(3);
	}

	@Test
	void 비회복_실패는_즉시_전파하고_한번만_시도한다() {
		SelfRetry retry = new SelfRetry(3);
		AtomicInteger calls = new AtomicInteger();
		assertThatThrownBy(() -> retry.call("embed", () -> {
			calls.incrementAndGet();
			throw new SelfCrawlException(SelfErrorClass.NOT_FOUND, "404");
		})).isInstanceOf(SelfCrawlException.class);
		assertThat(calls.get()).isEqualTo(1);
	}

	@Test
	void 회복가능_실패_3회_소진시_마지막_예외를_전파한다() {
		SelfRetry retry = new SelfRetry(3);
		AtomicInteger calls = new AtomicInteger();
		assertThatThrownBy(() -> retry.call("embed", () -> {
			calls.incrementAndGet();
			throw new SelfCrawlException(SelfErrorClass.TRANSPORT, "transport");
		})).isInstanceOf(SelfCrawlException.class);
		assertThat(calls.get()).isEqualTo(3);
	}

	// ── F3: 시간 예산(데드라인) ──────────────────────────────────────────

	@Test
	void 시간_예산을_넘기면_회복가능_에러여도_재시도_없이_즉시_전파한다() {
		SteppingClock clock = new SteppingClock();
		SelfRetry retry = new SelfRetry(3, Duration.ofMillis(100), clock);
		AtomicInteger calls = new AtomicInteger();

		assertThatThrownBy(() -> retry.call("comments", () -> {
			calls.incrementAndGet();
			clock.advance(Duration.ofMillis(200));   // 1회 시도가 예산을 넘겨 소비(느린 self 요청 흉내)
			throw new SelfCrawlException(SelfErrorClass.RECOVERABLE_401, "401");
		})).isInstanceOf(SelfCrawlException.class);

		assertThat(calls.get()).isEqualTo(1);
	}

	@Test
	void 시간_예산_내면_기존_3회_재시도를_유지한다() {
		SteppingClock clock = new SteppingClock();
		SelfRetry retry = new SelfRetry(3, Duration.ofSeconds(8), clock);
		AtomicInteger calls = new AtomicInteger();

		String r = retry.call("comments", () -> {
			int n = calls.incrementAndGet();
			if (n < 3) {
				throw new SelfCrawlException(SelfErrorClass.RECOVERABLE_401, "401");
			}
			return "ok";
		});

		assertThat(r).isEqualTo("ok");
		assertThat(calls.get()).isEqualTo(3);
	}
}
