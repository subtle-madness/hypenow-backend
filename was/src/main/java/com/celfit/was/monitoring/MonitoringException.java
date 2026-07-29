package com.celfit.was.monitoring;

/** 모니터링 통신 예외 공통 베이스 — 하위 2계열의 구분 축은 "같은 멱등키 재시도 가능성"(스펙 §4). */
public abstract class MonitoringException extends RuntimeException {

	protected MonitoringException(String message) {
		super(message);
	}

	protected MonitoringException(String message, Throwable cause) {
		super(message, cause);
	}
}
