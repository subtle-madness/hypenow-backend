package com.celfit.instagram.source;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** 폴백 WARN 속도제한 순수 판정 로직 — 로그 캡처 대신 shouldEmit()의 반환값(-1=억제, 0 이상=출력
 * +그동안 억제된 건수)만으로 검증한다. */
class RateLimitedWarnTest {

	@Test
	void 간격_내_두번째_호출은_억제된다() {
		AtomicLong now = new AtomicLong(0L);
		RateLimitedWarn limiter = new RateLimitedWarn(30_000L, now::get);

		assertThat(limiter.shouldEmit("k")).isEqualTo(0L);
		now.addAndGet(1_000L);
		assertThat(limiter.shouldEmit("k")).isEqualTo(-1L);
	}

	@Test
	void 간격_경과_후_재출력은_억제_건수를_포함한다() {
		AtomicLong now = new AtomicLong(0L);
		RateLimitedWarn limiter = new RateLimitedWarn(30_000L, now::get);

		limiter.shouldEmit("k");
		now.addAndGet(1_000L);
		limiter.shouldEmit("k"); // 억제 1건째
		now.addAndGet(1_000L);
		limiter.shouldEmit("k"); // 억제 2건째

		now.addAndGet(30_000L);
		assertThat(limiter.shouldEmit("k")).isEqualTo(2L);

		// 방출 직후엔 억제 카운트가 리셋된다 — 그 뒤 억제 1건만 쌓였으면 다음 출력엔 1건으로 보고된다.
		now.addAndGet(1_000L);
		assertThat(limiter.shouldEmit("k")).isEqualTo(-1L);
		now.addAndGet(30_000L);
		assertThat(limiter.shouldEmit("k")).isEqualTo(1L);
	}

	@Test
	void 키가_다르면_서로_독립적으로_판단한다() {
		AtomicLong now = new AtomicLong(0L);
		RateLimitedWarn limiter = new RateLimitedWarn(30_000L, now::get);

		assertThat(limiter.shouldEmit("a")).isEqualTo(0L);
		assertThat(limiter.shouldEmit("b")).isEqualTo(0L);
		assertThat(limiter.shouldEmit("a")).isEqualTo(-1L);
	}
}
