package com.celfit.monitoring.hiker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.celfit.instagram.source.HikerFetchException;
import com.celfit.monitoring.hiker.HikerConcurrencyLimiter.Lane;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Hiker 전송 동시 in-flight 상한(2026-09-03 브랜드 병렬 스윕) — 어떤 풀 조합에서도 총량이 상한을
 * 넘지 않고, 배치 부하가 사용자 대면 동기 경로를 굶기지 않는지 검증한다.
 */
class HikerConcurrencyLimiterTest {

	private final ExecutorService pool = Executors.newCachedThreadPool();
	private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

	@AfterEach
	void tearDown() {
		pool.shutdownNow();
	}

	private HikerConcurrencyLimiter limiter(int max, int syncReserved, Duration syncTimeout) {
		return new HikerConcurrencyLimiter(max, syncReserved, Duration.ofSeconds(5), syncTimeout, registry);
	}

	/** 상한 permits 안에서만 동시에 돈다 — 상한이 없으면 peak가 태스크 수만큼 올라간다. */
	@Test
	void 동시_in_flight는_상한을_넘지_않는다() throws Exception {
		HikerConcurrencyLimiter limiter = limiter(2, 0, Duration.ofSeconds(5));
		AtomicInteger inFlight = new AtomicInteger();
		AtomicInteger peak = new AtomicInteger();
		CountDownLatch reachedCap = new CountDownLatch(2);
		List<Future<String>> futures = new ArrayList<>();

		for (int i = 0; i < 4; i++) {
			futures.add(pool.submit(() -> limiter.call(Lane.BATCH, () -> {
				peak.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
				reachedCap.countDown();
				try {
					reachedCap.await(5, TimeUnit.SECONDS);   // 상한만큼은 실제로 겹친다
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
				inFlight.decrementAndGet();
				return "ok";
			})));
		}
		for (Future<String> f : futures) {
			assertThat(f.get(10, TimeUnit.SECONDS)).isEqualTo("ok");
		}

		assertThat(peak.get()).isEqualTo(2);
	}

	/**
	 * 동기 예약 퍼밋 — 배치가 자기 몫(max - reserved)을 다 써도 동기 경로는 즉시 통과한다. 배치가
	 * 동기를 굶히지 못한다는 것이 이 설계의 핵심 계약이다.
	 */
	@Test
	void 배치가_가득_차도_동기_경로는_즉시_통과한다() throws Exception {
		HikerConcurrencyLimiter limiter = limiter(3, 1, Duration.ofMillis(300));
		CountDownLatch batchHolding = new CountDownLatch(2);
		CountDownLatch releaseBatch = new CountDownLatch(1);
		for (int i = 0; i < 2; i++) {
			pool.submit(() -> limiter.call(Lane.BATCH, () -> {
				batchHolding.countDown();
				try {
					releaseBatch.await(10, TimeUnit.SECONDS);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
				return "batch";
			}));
		}
		assertThat(batchHolding.await(5, TimeUnit.SECONDS)).isTrue();   // 배치 몫 2/2 점유

		String sync = limiter.call(Lane.SYNC, () -> "sync");   // 짧은 타임아웃인데도 대기 없이 통과

		assertThat(sync).isEqualTo("sync");
		releaseBatch.countDown();
	}

	/** 배치는 자기 상한(max - reserved)까지만 — 예약분을 침범하지 않는다. */
	@Test
	void 배치_레인은_동기_예약분을_침범하지_않는다() throws Exception {
		HikerConcurrencyLimiter limiter = limiter(3, 1, Duration.ofSeconds(5));
		AtomicInteger inFlight = new AtomicInteger();
		AtomicInteger peak = new AtomicInteger();
		CountDownLatch reachedCap = new CountDownLatch(2);
		List<Future<String>> futures = new ArrayList<>();

		for (int i = 0; i < 4; i++) {
			futures.add(pool.submit(() -> limiter.call(Lane.BATCH, () -> {
				peak.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
				reachedCap.countDown();
				try {
					reachedCap.await(5, TimeUnit.SECONDS);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
				inFlight.decrementAndGet();
				return "ok";
			})));
		}
		for (Future<String> f : futures) {
			f.get(10, TimeUnit.SECONDS);
		}

		assertThat(peak.get()).isEqualTo(2);   // 3이 아니다 — 1개는 동기 예약
	}

	/** 획득 대기가 예산을 넘으면 벤더 장애와 같은 예외로 끊는다(동기 경로는 self 구조로 넘어간다). */
	@Test
	void 획득_대기가_타임아웃되면_HikerFetchException을_던진다() throws Exception {
		HikerConcurrencyLimiter limiter = limiter(1, 0, Duration.ofMillis(50));
		CountDownLatch holding = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		pool.submit(() -> limiter.call(Lane.BATCH, () -> {
			holding.countDown();
			try {
				release.await(10, TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			return "batch";
		}));
		assertThat(holding.await(5, TimeUnit.SECONDS)).isTrue();

		assertThatThrownBy(() -> limiter.call(Lane.SYNC, () -> "sync"))
				.isInstanceOf(HikerFetchException.class);

		release.countDown();
	}

	/** 본문이 실패해도 퍼밋은 반환된다 — 누수되면 상한이 서서히 0으로 조여 수집이 멎는다. */
	@Test
	void 본문_실패에도_퍼밋이_반환된다() {
		HikerConcurrencyLimiter limiter = limiter(1, 0, Duration.ofMillis(200));

		assertThatThrownBy(() -> limiter.call(Lane.BATCH, () -> {
			throw new IllegalStateException("전송 실패");
		})).isInstanceOf(IllegalStateException.class);

		assertThat(limiter.call(Lane.BATCH, () -> "ok")).isEqualTo("ok");
		assertThat(limiter.call(Lane.SYNC, () -> "ok")).isEqualTo("ok");
	}

	/** 전송 데코레이터는 위임만 하고 결과·예외를 그대로 통과시킨다. */
	@Test
	void 전송_데코레이터는_바디를_그대로_통과시킨다() {
		HikerConcurrencyLimiter limiter = limiter(2, 0, Duration.ofSeconds(1));
		List<String> seen = new ArrayList<>();

		String body = new ConcurrencyLimitedHikerHttp(path -> {
			seen.add(path);
			return "본문";
		}, limiter, Lane.BATCH).get("/v2/media/info/by/code?code=X");

		assertThat(body).isEqualTo("본문");
		assertThat(seen).containsExactly("/v2/media/info/by/code?code=X");
	}
}
