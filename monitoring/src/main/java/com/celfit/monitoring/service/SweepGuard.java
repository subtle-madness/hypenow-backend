package com.celfit.monitoring.service;

import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;

/**
 * 스윕 동시 실행 가드 — 크론 경로({@link SweepScheduler})와 수동 트리거 경로
 * ({@link SweepCommandService})가 하나의 인스턴스를 공유한다. 크론 스윕이 아직 도는데 수동
 * 트리거가 겹치거나, 그 반대로 겹치는 것도 이 가드 하나로 막는다.
 *
 * <p>{@link java.util.concurrent.locks.ReentrantLock}이 아니라 {@link AtomicBoolean} CAS를 쓴다 —
 * 수동 트리거 경로는 획득(SweepCommandService.start(), HTTP 요청 스레드)과 해제(전용 executor의
 * manual-sweep 스레드)가 서로 다른 스레드에서 일어난다. ReentrantLock.unlock()은 lock()을 부른
 * 바로 그 스레드만 호출할 수 있어(다른 스레드가 부르면 IllegalMonitorStateException), 이 교차 스레드
 * 해제 패턴과 맞지 않는다 — 실측: executor.submit()의 Runnable 안에서 그 예외가 나면 아무도
 * Future#get()을 호출하지 않아 조용히 삼켜지고, 가드가 영원히 눌린 채로 남는 재현 가능한 버그였다.
 * AtomicBoolean은 스레드 소유권이 없어 이 문제가 없다.
 *
 * <p>monitoring은 단일 인스턴스로만 배포되므로 in-process 상태로 충분하다(다중 인스턴스가 되면
 * DB 어드바이저리 락 등으로 재검토가 필요하다 — 지금은 범위 밖).
 */
@Component
public class SweepGuard {

	private final AtomicBoolean running = new AtomicBoolean(false);

	/** 즉시 획득 시도 — 이미 실행 중이면 false. 획득 성공 시 해제는 호출부의 finally 책임(어느 스레드에서든 가능). */
	public boolean tryAcquire() {
		return running.compareAndSet(false, true);
	}

	public void release() {
		running.set(false);
	}

	/** GET /api/sweeps/latest가 "실행 중" 여부를 판단하는 데 쓴다(DB completed_at IS NULL이 아니라 이 상태로 판단). */
	public boolean isRunning() {
		return running.get();
	}
}
