package com.celfit.was.v1.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class PagePrefetcherTest {

	@Test
	void 다음_페이지_판정() {
		assertThat(PagePrefetcher.hasNextPage(100, 100, 0, 250)).isTrue();   // 다음 페이지 있음
		assertThat(PagePrefetcher.hasNextPage(100, 100, 200, 250)).isFalse(); // offset+limit(300) >= total(250) → 다음 없음
		assertThat(PagePrefetcher.hasNextPage(50, 100, 0, 50)).isFalse();     // 부분 페이지 = 마지막
		assertThat(PagePrefetcher.hasNextPage(100, 100, 100, 200)).isFalse(); // 정확히 소진
	}

	@Test
	void 작업이_실행되고_예외는_삼킨다() throws Exception {
		PagePrefetcher prefetcher = new PagePrefetcher();
		CountDownLatch ran = new CountDownLatch(1);
		prefetcher.prefetch(() -> {
			ran.countDown();
			throw new IllegalStateException("boom"); // 삼켜져야 함
		});
		assertThat(ran.await(2, TimeUnit.SECONDS)).isTrue();
		CountDownLatch after = new CountDownLatch(1);
		prefetcher.prefetch(after::countDown); // 이전 예외 후에도 풀 생존
		assertThat(after.await(2, TimeUnit.SECONDS)).isTrue();
	}
}
