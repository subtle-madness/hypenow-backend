package com.celfit.was.v1.perfdashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;

import com.celfit.was.v1.perfdashboard.PerformanceContentAssembler.DashboardIndex;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 동시 조립 합류 계약(2026-08-31 설계 §2) — <b>보관하지 않는다</b>는 것까지 포함해 고정한다.
 * 조립 mock은 호출마다 <b>새 인스턴스</b>를 돌려주므로, 합류가 실패하면 반환 인스턴스가 갈려서
 * 테스트가 즉시 깨진다(횟수 단정과 인스턴스 단정이 서로를 보강한다).
 */
@ExtendWith(MockitoExtension.class)
class DashboardIndexCoalescerTest {

	private static final String VERSION = "0123456789abcdef0123456789abcdef";
	private static final String OTHER_VERSION = "ffffffffffffffffffffffffffffffff";
	private static final long USER_ID = 7L;
	private static final long OTHER_USER_ID = 8L;
	/** 대기 상한 — 합류 실패 시 무한 대기로 굳지 않게 모든 대기에 건다. */
	private static final long TIMEOUT_SECONDS = 5;

	@Mock
	PerformanceContentAssembler assembler;

	DashboardIndexCoalescer coalescer;

	@BeforeEach
	void 합류기() {
		coalescer = new DashboardIndexCoalescer(assembler);
	}

	@Test
	void 보관하지_않는다_순차_호출은_매번_다시_조립한다() {
		given(assembler.index(USER_ID)).willAnswer(invocation -> emptyIndex());

		DashboardIndex first = coalescer.index(VERSION, USER_ID);
		DashboardIndex second = coalescer.index(VERSION, USER_ID);

		assertThat(second).isNotSameAs(first);
		then(assembler).should(times(2)).index(USER_ID);
	}

	@Test
	void 동시_진입은_조립_1회로_접히고_전원_같은_인스턴스를_받는다() throws Exception {
		CountDownLatch 리더진입 = new CountDownLatch(1);
		CountDownLatch 리더해제 = new CountDownLatch(1);
		AtomicInteger 조립횟수 = new AtomicInteger();
		given(assembler.index(USER_ID)).willAnswer(invocation -> {
			조립횟수.incrementAndGet();
			리더진입.countDown();
			assertThat(리더해제.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
			return emptyIndex();
		});

		List<Thread> 합류자스레드 = Collections.synchronizedList(new ArrayList<>());
		ExecutorService pool = Executors.newFixedThreadPool(7);
		try {
			Future<DashboardIndex> 리더 = pool.submit(() -> coalescer.index(VERSION, USER_ID));
			assertThat(리더진입.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();

			List<Future<DashboardIndex>> 합류자 = new ArrayList<>();
			for (int i = 0; i < 6; i++) {
				합류자.add(pool.submit(() -> {
					합류자스레드.add(Thread.currentThread());
					return coalescer.index(VERSION, USER_ID);
				}));
			}
			awaitParked(합류자스레드, 6);   // 6명이 전부 대기에 들어간 뒤에 리더를 푼다
			리더해제.countDown();

			DashboardIndex expected = 리더.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
			for (Future<DashboardIndex> f : 합류자) {
				assertThat(f.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isSameAs(expected);
			}
			assertThat(조립횟수).hasValue(1);
		} finally {
			pool.shutdownNow();
		}
	}

	@Test
	void 버전이_다르면_합류하지_않고_각자_조립한다() throws Exception {
		CountDownLatch 둘다진입 = new CountDownLatch(2);
		CountDownLatch 해제 = new CountDownLatch(1);
		given(assembler.index(USER_ID)).willAnswer(invocation -> {
			둘다진입.countDown();
			assertThat(해제.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
			return emptyIndex();
		});

		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			Future<DashboardIndex> a = pool.submit(() -> coalescer.index(VERSION, USER_ID));
			Future<DashboardIndex> b = pool.submit(() -> coalescer.index(OTHER_VERSION, USER_ID));

			// 둘 다 조립에 들어왔다는 것 자체가 "합류하지 않았다"의 증거다.
			assertThat(둘다진입.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
			해제.countDown();

			assertThat(a.get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
					.isNotSameAs(b.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
			then(assembler).should(times(2)).index(USER_ID);
		} finally {
			pool.shutdownNow();
		}
	}

	@Test
	void 유저가_다르면_합류하지_않고_각자_조립한다() throws Exception {
		// 회귀 가드 — 버전키가 유저를 해싱한다고 키에서 userId를 빼면 A유저의 대시보드가 B유저에게 나간다.
		CountDownLatch 둘다진입 = new CountDownLatch(2);
		CountDownLatch 해제 = new CountDownLatch(1);
		given(assembler.index(USER_ID)).willAnswer(invocation -> {
			둘다진입.countDown();
			assertThat(해제.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
			return emptyIndex();
		});
		given(assembler.index(OTHER_USER_ID)).willAnswer(invocation -> {
			둘다진입.countDown();
			assertThat(해제.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
			return emptyIndex();
		});

		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			Future<DashboardIndex> a = pool.submit(() -> coalescer.index(VERSION, USER_ID));
			Future<DashboardIndex> b = pool.submit(() -> coalescer.index(VERSION, OTHER_USER_ID));

			// 둘 다 조립에 들어왔다는 것 자체가 "합류하지 않았다"의 증거다.
			assertThat(둘다진입.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
			해제.countDown();

			assertThat(a.get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
					.isNotSameAs(b.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
			then(assembler).should().index(USER_ID);
			then(assembler).should().index(OTHER_USER_ID);
		} finally {
			pool.shutdownNow();
		}
	}

	@Test
	void 리더가_실패해도_보관되지_않아_다음_호출은_새로_조립한다() {
		given(assembler.index(USER_ID))
				.willThrow(new IllegalStateException("조립 실패"))
				.willAnswer(invocation -> emptyIndex());

		assertThatThrownBy(() -> coalescer.index(VERSION, USER_ID))
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("조립 실패");
		assertThat(coalescer.index(VERSION, USER_ID)).isNotNull();
		then(assembler).should(times(2)).index(USER_ID);
	}

	@Test
	void 리더의_예외는_합류자에게_포장_없이_전파된다() throws Exception {
		CountDownLatch 리더진입 = new CountDownLatch(1);
		CountDownLatch 리더해제 = new CountDownLatch(1);
		given(assembler.index(USER_ID)).willAnswer(invocation -> {
			리더진입.countDown();
			assertThat(리더해제.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
			throw new IllegalStateException("조립 실패");
		});

		List<Thread> 합류자스레드 = Collections.synchronizedList(new ArrayList<>());
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			Future<DashboardIndex> 리더 = pool.submit(() -> coalescer.index(VERSION, USER_ID));
			assertThat(리더진입.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
			Future<DashboardIndex> 합류자 = pool.submit(() -> {
				합류자스레드.add(Thread.currentThread());
				return coalescer.index(VERSION, USER_ID);
			});
			awaitParked(합류자스레드, 1);
			리더해제.countDown();

			// CompletionException이 벗겨지지 않으면 cause가 그것이 되어 이 단정이 깨진다.
			assertThatThrownBy(() -> 합류자.get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
					.isInstanceOf(ExecutionException.class)
					.hasCauseInstanceOf(IllegalStateException.class);
			assertThatThrownBy(() -> 리더.get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
					.isInstanceOf(ExecutionException.class)
					.hasCauseInstanceOf(IllegalStateException.class);
		} finally {
			pool.shutdownNow();
		}
	}

	/** 합류자 n명이 전부 대기 상태로 들어갈 때까지 — join()에 진입했다는 신호다. */
	private static void awaitParked(List<Thread> threads, int n) {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
		while (System.nanoTime() < deadline) {
			synchronized (threads) {
				if (threads.size() == n && threads.stream().allMatch(t -> {
					Thread.State state = t.getState();
					return state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING;
				})) {
					return;
				}
			}
			Thread.onSpinWait();
		}
		throw new AssertionError("합류자 " + n + "명이 대기 상태로 들어가지 않았다");
	}

	/** 조립 결과 자리를 채우는 빈 인덱스 — 호출마다 새 인스턴스여야 한다(합류 판별의 근거). */
	private static DashboardIndex emptyIndex() {
		return new DashboardIndex(USER_ID, List.of(), null, Set.of(), Map.of(), Map.of(), Map.of(), Map.of());
	}
}
