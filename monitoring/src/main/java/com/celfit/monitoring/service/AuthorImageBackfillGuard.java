package com.celfit.monitoring.service;

import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;

/**
 * 이미지 백필 동시 실행 가드 — {@link SweepGuard}와 동일한 근거로 {@link AtomicBoolean} CAS를 쓴다
 * (획득은 HTTP 요청 스레드, 해제는 전용 executor 스레드 — 스레드 소유권이 있는
 * {@link java.util.concurrent.locks.ReentrantLock}은 교차 스레드 해제와 맞지 않는다).
 *
 * <p>스윕 가드와 분리한 이유: 이 백필은 DailySweepJob과 무관한 별도 동작(어드민 수동 트리거 전용,
 * 상시 스케줄 없음)이라 공유 가드를 쓰면 스윕과 백필이 서로 차단하는 의도치 않은 결합이 생긴다.
 */
@Component
public class AuthorImageBackfillGuard {

	private final AtomicBoolean running = new AtomicBoolean(false);

	/** 즉시 획득 시도 — 이미 실행 중이면 false. 획득 성공 시 해제는 호출부의 finally 책임(어느 스레드에서든 가능). */
	public boolean tryAcquire() {
		return running.compareAndSet(false, true);
	}

	public void release() {
		running.set(false);
	}

	public boolean isRunning() {
		return running.get();
	}
}
