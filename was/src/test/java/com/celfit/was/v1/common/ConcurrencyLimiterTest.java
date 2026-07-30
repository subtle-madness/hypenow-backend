package com.celfit.was.v1.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** ConcurrencyLimiter(벌크헤드) 단위 테스트 — RateLimiterTest 스타일 참고. */
class ConcurrencyLimiterTest {

	@Test
	void permit_내_요청은_허용된다() {
		ConcurrencyLimiter limiter = new ConcurrencyLimiter(2, 50);

		assertThat(limiter.tryAcquire()).isTrue();
		assertThat(limiter.tryAcquire()).isTrue();
	}

	@Test
	void permit_초과_요청은_짧은_대기_후_거부된다() {
		ConcurrencyLimiter limiter = new ConcurrencyLimiter(2, 50);

		assertThat(limiter.tryAcquire()).isTrue();
		assertThat(limiter.tryAcquire()).isTrue();

		// 세 번째는 permit이 없어 acquireTimeout(50ms) 안에 못 얻고 거부된다
		assertThat(limiter.tryAcquire()).isFalse();
	}

	@Test
	void release_후에는_permit이_재사용_가능하다() {
		ConcurrencyLimiter limiter = new ConcurrencyLimiter(1, 50);

		assertThat(limiter.tryAcquire()).isTrue();
		assertThat(limiter.tryAcquire()).isFalse();

		limiter.release();

		assertThat(limiter.tryAcquire()).isTrue();
	}

	@Test
	void 예외_발생_경로에서도_finally의_release로_permit이_복구된다() {
		ConcurrencyLimiter limiter = new ConcurrencyLimiter(1, 50);

		assertThat(limiter.tryAcquire()).isTrue();
		assertThat(limiter.availablePermits()).isEqualTo(0);

		try {
			try {
				throw new RuntimeException("의도된 예외 — 처리 중 실패 시나리오");
			} finally {
				limiter.release();
			}
		} catch (RuntimeException ignored) {
			// 컨트롤러의 try/finally 관용구 재현 — 예외가 release를 건너뛰지 않는지만 검증
		}

		assertThat(limiter.availablePermits()).isEqualTo(1);
		assertThat(limiter.tryAcquire()).isTrue();
	}

	@Test
	void 동시성_환경에서도_동시_획득_수는_permit을_넘지_않는다() throws InterruptedException {
		int permits = 3;
		int threads = 9;
		ConcurrencyLimiter limiter = new ConcurrencyLimiter(permits, 1000);

		ExecutorService pool = Executors.newFixedThreadPool(threads);
		CountDownLatch startLatch = new CountDownLatch(1);
		CountDownLatch doneLatch = new CountDownLatch(threads);
		AtomicInteger concurrentCount = new AtomicInteger();
		AtomicInteger maxConcurrent = new AtomicInteger();
		AtomicInteger acquiredCount = new AtomicInteger();

		for (int i = 0; i < threads; i++) {
			pool.submit(() -> {
				try {
					startLatch.await();
					if (limiter.tryAcquire()) {
						acquiredCount.incrementAndGet();
						int current = concurrentCount.incrementAndGet();
						maxConcurrent.updateAndGet(max -> Math.max(max, current));
						Thread.sleep(30);
						concurrentCount.decrementAndGet();
						limiter.release();
					}
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				} finally {
					doneLatch.countDown();
				}
			});
		}

		startLatch.countDown();
		boolean completed = doneLatch.await(5, TimeUnit.SECONDS);
		pool.shutdown();

		assertThat(completed).isTrue();
		// 대기 타임아웃(1초)이 충분히 넉넉해 9개 스레드 모두 결국 permit을 획득한다
		assertThat(acquiredCount.get()).isEqualTo(threads);
		assertThat(maxConcurrent.get()).isLessThanOrEqualTo(permits);
	}
}
