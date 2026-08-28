package com.celfit.monitoring.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.monitoring.domain.BrandStatus;
import com.celfit.monitoring.store.BrandRepository;
import com.celfit.monitoring.store.BrandRow;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * 미보강 이관분 기동 즉시 백필 러너(2026-08-28 사용자 지시) —
 * {@link AdDisclosureBackfillStartupRunnerTest}와 동형 골격. {@link
 * UnenrichedBackfillStartupRunner#onApplicationReady}가 블로킹 없이 즉시 반환하고, 실제
 * {@code backfillUnenriched} 순회는 별도 스레드에서 브랜드마다 격리돼 도는지 검증한다.
 */
class UnenrichedBackfillStartupRunnerTest {

	private static final class StubBrands extends BrandRepository {
		List<BrandRow> active = List.of();

		StubBrands() {
			super(null);
		}

		@Override
		public List<BrandRow> findActive() {
			return active;
		}
	}

	private static final class StubDirectCollect extends BrandDirectCollectService {
		final List<Long> calls = new java.util.concurrent.CopyOnWriteArrayList<>();
		final AtomicInteger totalCalls = new AtomicInteger();
		final CountDownLatch latch;
		final ConcurrentHashMap<Long, Integer> results = new ConcurrentHashMap<>();
		Set<Long> failing = Set.of();
		volatile String callingThreadName;

		StubDirectCollect(int expectedCalls) {
			super(null, null, null, null, null, 300);
			this.latch = new CountDownLatch(expectedCalls);
		}

		@Override
		public int backfillUnenriched(BrandRow brand) {
			calls.add(brand.id());
			totalCalls.incrementAndGet();
			callingThreadName = Thread.currentThread().getName();
			try {
				if (failing.contains(brand.id())) {
					throw new IllegalStateException("기동 백필 실패 주입 — brand=" + brand.id());
				}
				return results.getOrDefault(brand.id(), 0);
			} finally {
				latch.countDown();
			}
		}
	}

	private static BrandRow brand(long id, String username) {
		return new BrandRow(id, username, String.valueOf(id), BrandStatus.ACTIVE, null, 12, true);
	}

	@Test
	void 킬_스위치가_켜져있으면_별도_스레드에서_활성_브랜드_전부의_backfillUnenriched를_호출한다() throws InterruptedException {
		var brands = new StubBrands();
		brands.active = List.of(brand(1, "first"), brand(2, "second"));
		var directCollect = new StubDirectCollect(2);
		String testThreadName = Thread.currentThread().getName();

		UnenrichedBackfillStartupRunner runner = new UnenrichedBackfillStartupRunner(brands, directCollect, true);
		runner.onApplicationReady();   // 부팅 블로킹 금지 — 이 호출 자체는 즉시 반환해야 한다

		assertThat(directCollect.latch.await(5, TimeUnit.SECONDS)).as("별도 스레드에서 순회가 돌아야 한다").isTrue();
		assertThat(directCollect.calls).containsExactlyInAnyOrder(1L, 2L);
		assertThat(directCollect.callingThreadName).isNotEqualTo(testThreadName);
	}

	@Test
	void 킬_스위치가_꺼져있으면_backfillUnenriched를_호출하지_않는다() throws InterruptedException {
		var brands = new StubBrands();
		brands.active = List.of(brand(1, "first"));
		var directCollect = new StubDirectCollect(1);

		UnenrichedBackfillStartupRunner runner = new UnenrichedBackfillStartupRunner(brands, directCollect, false);
		runner.onApplicationReady();

		assertThat(directCollect.latch.await(200, TimeUnit.MILLISECONDS)).isFalse();   // 스레드조차 안 뜬다
		assertThat(directCollect.totalCalls.get()).isZero();
	}

	/** 한 브랜드의 백필 실패가 나머지 브랜드 순회를 막지 않는다 — BrandSweepJob과 같은 격리 규율. */
	@Test
	void 한_브랜드의_실패는_격리되고_나머지_브랜드는_계속_처리된다() throws InterruptedException {
		var brands = new StubBrands();
		brands.active = List.of(brand(1, "boom"), brand(2, "second"));
		var directCollect = new StubDirectCollect(2);
		directCollect.failing = Set.of(1L);

		UnenrichedBackfillStartupRunner runner = new UnenrichedBackfillStartupRunner(brands, directCollect, true);
		runner.onApplicationReady();   // 예외가 새면(데몬 스레드 uncaught) 테스트 프로세스에 영향 없이 잡혀야 한다

		assertThat(directCollect.latch.await(5, TimeUnit.SECONDS)).isTrue();
		assertThat(directCollect.calls).containsExactlyInAnyOrder(1L, 2L);   // 실패한 브랜드도 시도는 됐다
	}

	@Test
	void 활성_브랜드가_없으면_콜_없이_조용히_끝난다() throws InterruptedException {
		var brands = new StubBrands();
		brands.active = List.of();
		var directCollect = new StubDirectCollect(0);

		UnenrichedBackfillStartupRunner runner = new UnenrichedBackfillStartupRunner(brands, directCollect, true);
		runner.onApplicationReady();

		Thread.sleep(200);   // 데몬 스레드가 조용히 즉시 끝나는지 관측할 시간
		assertThat(directCollect.totalCalls.get()).isZero();
	}
}
