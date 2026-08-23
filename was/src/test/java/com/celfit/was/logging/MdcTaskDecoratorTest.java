package com.celfit.was.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/**
 * 실행기 MDC 전파 — 등록 실행기(afterCommit 콜백에서 submit)로 넘어간 작업이 원 요청의
 * requestId를 이어받아야 was 접수 로그와 monitoring 처리 로그가 같은 ID로 묶인다.
 * 워커 스레드는 풀에서 재사용되므로 실행 후 원상 복구가 없으면 다음 작업에 ID가 샌다.
 */
class MdcTaskDecoratorTest {

	private final MdcTaskDecorator decorator = new MdcTaskDecorator();

	@AfterEach
	void clearMdc() {
		MDC.clear();
	}

	@Test
	void 제출_시점의_MDC를_실행_시점에_복원한다() throws InterruptedException {
		MDC.put("requestId", "abc12345");
		AtomicReference<String> seen = new AtomicReference<>();
		Runnable decorated = decorator.decorate(() -> seen.set(MDC.get("requestId")));
		MDC.clear();

		Thread worker = new Thread(decorated);
		worker.start();
		worker.join();

		assertThat(seen.get()).isEqualTo("abc12345");
	}

	@Test
	void 실행_후_워커_스레드의_MDC를_정리한다() throws InterruptedException {
		MDC.put("requestId", "abc12345");
		Runnable decorated = decorator.decorate(() -> {
		});
		AtomicReference<String> afterRun = new AtomicReference<>();

		Thread worker = new Thread(() -> {
			decorated.run();
			afterRun.set(MDC.get("requestId"));
		});
		worker.start();
		worker.join();

		assertThat(afterRun.get()).isNull();
	}

	@Test
	void 제출_시점_MDC가_비어_있으면_실행_시점에도_비어_있다() throws InterruptedException {
		Runnable decorated = decorator.decorate(() -> {
		});
		AtomicReference<String> seen = new AtomicReference<>();

		Thread worker = new Thread(() -> {
			MDC.put("requestId", "잔류값");
			decorated.run();
			seen.set(MDC.get("requestId"));
		});
		worker.start();
		worker.join();

		// 빈 컨텍스트로 덮어써 워커에 남아 있던 잔류값도 지워져야 한다
		assertThat(seen.get()).isNull();
	}
}
