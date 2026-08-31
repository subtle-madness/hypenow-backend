package com.celfit.instagram.source.self;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SelfRetryTest {

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
}
