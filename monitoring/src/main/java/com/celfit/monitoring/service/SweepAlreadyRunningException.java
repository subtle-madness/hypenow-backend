package com.celfit.monitoring.service;

/** 수동 스윕 트리거가 이미 실행 중인 스윕과 겹칠 때(409) — {@link SweepGuard} 획득 실패가 원인이다. */
public class SweepAlreadyRunningException extends RuntimeException {

	public SweepAlreadyRunningException() {
		super("스윕이 이미 실행 중입니다.");
	}
}
