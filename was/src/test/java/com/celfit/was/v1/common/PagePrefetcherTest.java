package com.celfit.was.v1.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class PagePrefetcherTest {

	private final PagePrefetcher prefetcher = new PagePrefetcher();

	@AfterEach
	void tearDown() {
		prefetcher.shutdown();
	}

	@Test
	void 다음_페이지_판정() {
		assertThat(PagePrefetcher.hasNextPage(100, 100, 0, 250)).isTrue();   // 다음 페이지 있음
		assertThat(PagePrefetcher.hasNextPage(100, 100, 200, 250)).isFalse(); // offset+limit(300) >= total(250) → 다음 없음
		assertThat(PagePrefetcher.hasNextPage(50, 100, 0, 50)).isFalse();     // 부분 페이지 = 마지막
		assertThat(PagePrefetcher.hasNextPage(100, 100, 100, 200)).isFalse(); // 정확히 소진
	}

	@Test
	void 예외가_삼켜져_워커_스레드가_살아남는다() throws Exception {
		AtomicLong firstThread = new AtomicLong();
		AtomicLong secondThread = new AtomicLong();
		CountDownLatch ran = new CountDownLatch(1);
		prefetcher.prefetch(() -> {
			firstThread.set(Thread.currentThread().threadId());
			ran.countDown();
			throw new IllegalStateException("boom"); // 삼켜져야 함
		});
		assertThat(ran.await(2, TimeUnit.SECONDS)).isTrue();
		CountDownLatch after = new CountDownLatch(1);
		prefetcher.prefetch(() -> {
			secondThread.set(Thread.currentThread().threadId());
			after.countDown();
		});
		assertThat(after.await(2, TimeUnit.SECONDS)).isTrue();
		// catch가 없으면 워커가 죽고 교체돼 스레드 id가 달라진다
		assertThat(secondThread.get()).isEqualTo(firstThread.get());
	}
}
