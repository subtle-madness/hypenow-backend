package com.celfit.monitoring.service;

import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;

/**
 * 브랜드 스윕 동시 실행 가드 — 2026-09 열거 실패 재시도 스케줄러 신설로 {@link BrandSweepScheduler}
 * 내부 private {@code AtomicBoolean}을 공유 컴포넌트로 추출했다(캠페인 {@link SweepGuard}와 동형·
 * 같은 CAS 근거 — {@link java.util.concurrent.locks.ReentrantLock}이 아니라 AtomicBoolean인 이유는
 * SweepGuard javadoc 참조).
 *
 * <p>추출 전에는 크론 경로 자기 자신만 겹침을 막았지만, 이제 {@link BrandBackfillRetryJob}이 이
 * 가드가 잡혀 있는(= 야간 브랜드 스윕이 도는) 동안 재시도 틱 전체를 스킵한다 — 02:00 스윕이 도는
 * 같은 브랜드에 {@code sweepCore}가 겹쳐 도는 것과, 스윕의 전역 Hiker 콜 예산이 재시도와 경합하는
 * 것을 구조적으로 막는다. {@code BrandSweepScheduler}의 동작 자체는 이 추출로 바뀌지 않는다.
 *
 * <p>monitoring은 단일 인스턴스로만 배포되므로 in-process 상태로 충분하다(SweepGuard javadoc과
 * 같은 전제).
 */
@Component
public class BrandSweepGuard {

	private final AtomicBoolean running = new AtomicBoolean(false);

	/** 즉시 획득 시도 — 이미 실행 중이면 false. 해제는 호출부의 finally 책임. */
	public boolean tryAcquire() {
		return running.compareAndSet(false, true);
	}

	public void release() {
		running.set(false);
	}

	/** {@link BrandBackfillRetryJob}이 재시도 틱을 스킵할지 판단하는 데 쓴다. */
	public boolean isRunning() {
		return running.get();
	}
}
