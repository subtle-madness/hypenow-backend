package com.celfit.monitoring.ad;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * 기동 즉시 백필 러너(2026-08-18) — {@link AdDisclosureBackfillStartupRunner#onApplicationReady}가
 * 블로킹 없이 즉시 반환하고, 실제 {@code backfillUnjudged} 호출은 별도 스레드에서 비동기로
 * 일어나는지 검증한다. 킬 스위치·실패 격리도 함께 확인한다.
 */
class AdDisclosureBackfillStartupRunnerTest {

	private static final class StubAdJudge extends AdDisclosureJudgeService {
		final AtomicInteger calls = new AtomicInteger();
		final CountDownLatch latch = new CountDownLatch(1);
		RuntimeException failing;
		volatile String callingThreadName;

		StubAdJudge() {
			super(null, null, null);
		}

		@Override
		public BackfillOutcome backfillUnjudged() {
			calls.incrementAndGet();
			callingThreadName = Thread.currentThread().getName();
			try {
				if (failing != null) {
					throw failing;
				}
				return new BackfillOutcome(0, 0);
			} finally {
				latch.countDown();
			}
		}
	}

	@Test
	void 킬_스위치가_켜져있으면_별도_스레드에서_backfillUnjudged를_비동기로_호출한다() throws InterruptedException {
		StubAdJudge adJudge = new StubAdJudge();
		AdDisclosureBackfillStartupRunner runner = new AdDisclosureBackfillStartupRunner(adJudge, true);
		String testThreadName = Thread.currentThread().getName();

		runner.onApplicationReady();   // 부팅 블로킹 금지 — 이 호출 자체는 즉시 반환해야 한다

		assertThat(adJudge.latch.await(5, TimeUnit.SECONDS)).as("별도 스레드에서 backfillUnjudged가 호출돼야 한다").isTrue();
		assertThat(adJudge.calls.get()).isEqualTo(1);
		assertThat(adJudge.callingThreadName).isNotEqualTo(testThreadName);
	}

	@Test
	void 킬_스위치가_꺼져있으면_backfillUnjudged를_호출하지_않는다() throws InterruptedException {
		StubAdJudge adJudge = new StubAdJudge();
		AdDisclosureBackfillStartupRunner runner = new AdDisclosureBackfillStartupRunner(adJudge, false);

		runner.onApplicationReady();

		assertThat(adJudge.latch.await(200, TimeUnit.MILLISECONDS)).isFalse();   // 스레드조차 안 뜬다
		assertThat(adJudge.calls.get()).isZero();
	}

	@Test
	void backfillUnjudged_실패는_격리되어_새지_않는다() throws InterruptedException {
		StubAdJudge adJudge = new StubAdJudge();
		adJudge.failing = new IllegalStateException("기동 백필 실패 주입");
		AdDisclosureBackfillStartupRunner runner = new AdDisclosureBackfillStartupRunner(adJudge, true);

		runner.onApplicationReady();   // 예외가 새면(데몬 스레드 uncaught) 테스트 프로세스에 영향 없이 잡혀야 한다

		assertThat(adJudge.latch.await(5, TimeUnit.SECONDS)).isTrue();
		assertThat(adJudge.calls.get()).isEqualTo(1);
	}
}
