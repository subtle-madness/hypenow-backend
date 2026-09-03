package com.celfit.monitoring.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.celfit.instagram.source.PostInfo;
import com.celfit.instagram.source.SubjectNotFoundException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** 스윕 런 1회 스코프의 원시 fetch 캐시 — 중복 제거·부재 캐시·일시 실패 비캐시·동시 진입 단일화. */
class SweepPostCacheTest {

	private static PostInfo post(String code) {
		return new PostInfo(code, "u", null, null, "1", "clips", null, null, 1L, 0L, 0L, null, null, null,
				null, null, null, null, null, true, false, false);
	}

	@Test
	void 같은_코드는_한_번만_받아오고_이후는_캐시로_돌려준다() {
		SweepPostCache cache = new SweepPostCache();
		AtomicInteger loads = new AtomicInteger();

		PostInfo first = cache.fetch("A", () -> {
			loads.incrementAndGet();
			return post("A");
		});
		PostInfo second = cache.fetch("A", () -> {
			loads.incrementAndGet();
			return post("A");
		});

		assertThat(loads.get()).isEqualTo(1);
		assertThat(second).isSameAs(first);
		assertThat(cache.loads()).isEqualTo(1);
		assertThat(cache.hits()).isEqualTo(1);
	}

	@Test
	void 코드가_다르면_각자_받아온다() {
		SweepPostCache cache = new SweepPostCache();
		AtomicInteger loads = new AtomicInteger();

		cache.fetch("A", () -> {
			loads.incrementAndGet();
			return post("A");
		});
		cache.fetch("B", () -> {
			loads.incrementAndGet();
			return post("B");
		});

		assertThat(loads.get()).isEqualTo(2);
		assertThat(cache.hits()).isZero();
	}

	/** 부재(삭제·비공개)도 캐시한다 — 브랜드마다 같은 404를 재과금하지 않기 위함. */
	@Test
	void 부재_결과도_캐시돼_두_번째_호출은_콜_없이_같은_예외를_받는다() {
		SweepPostCache cache = new SweepPostCache();
		AtomicInteger loads = new AtomicInteger();

		assertThatThrownBy(() -> cache.fetch("Gone", () -> {
			loads.incrementAndGet();
			throw new SubjectNotFoundException("없음: Gone");
		})).isInstanceOf(SubjectNotFoundException.class);

		assertThatThrownBy(() -> cache.fetch("Gone", () -> {
			loads.incrementAndGet();
			return post("Gone");
		})).isInstanceOf(SubjectNotFoundException.class);

		assertThat(loads.get()).isEqualTo(1);
	}

	/** 일시 실패(타임아웃·5xx)는 캐시하지 않는다 — 다음 브랜드가 다시 시도할 수 있어야 한다. */
	@Test
	void 일시_실패는_캐시되지_않아_다음_호출이_다시_시도한다() {
		SweepPostCache cache = new SweepPostCache();
		AtomicInteger loads = new AtomicInteger();

		assertThatThrownBy(() -> cache.fetch("Flaky", () -> {
			loads.incrementAndGet();
			throw new IllegalStateException("일시 장애");
		})).isInstanceOf(IllegalStateException.class);

		PostInfo recovered = cache.fetch("Flaky", () -> {
			loads.incrementAndGet();
			return post("Flaky");
		});

		assertThat(loads.get()).isEqualTo(2);
		assertThat(recovered.shortCode()).isEqualTo("Flaky");
	}

	/**
	 * 동시 진입 단일화 — 브랜드 병렬 스윕에서 두 브랜드가 같은 코드에 <b>동시에</b> 닿아도 콜은
	 * 1회다. 늦게 온 쪽은 먼저 온 쪽의 완료를 기다렸다가 같은 결과를 받는다.
	 */
	@Test
	void 동시에_같은_코드에_닿아도_콜은_한_번이다() throws Exception {
		SweepPostCache cache = new SweepPostCache();
		AtomicInteger loads = new AtomicInteger();
		CountDownLatch started = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			Future<PostInfo> slow = pool.submit(() -> cache.fetch("Shared", () -> {
				loads.incrementAndGet();
				started.countDown();
				try {
					release.await(10, TimeUnit.SECONDS);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
				return post("Shared");
			}));
			assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
			Future<PostInfo> waiter = pool.submit(() -> cache.fetch("Shared", () -> {
				loads.incrementAndGet();
				return post("Shared");
			}));
			release.countDown();

			assertThat(waiter.get(10, TimeUnit.SECONDS)).isSameAs(slow.get(10, TimeUnit.SECONDS));
			assertThat(loads.get()).isEqualTo(1);
		} finally {
			pool.shutdownNow();
		}
	}
}
