package com.celfit.was.monitoring;

/**
 * 모니터링 계층 예외 공통 베이스. 통신 2계열(Api=확정 실패·재시도 무의미 /
 * Unavailable=전송 실패·같은 멱등키 재시도 가능)로 나뉜다. was 자체 소유 검증 실패(캠페인 not found 등)는
 * 통신과 무관해 이 계층에 속하지 않고, v1 레이어의 V1ApiException.notFound가 처리한다.
 */
public abstract class MonitoringException extends RuntimeException {

	protected MonitoringException(String message) {
		super(message);
	}

	protected MonitoringException(String message, Throwable cause) {
		super(message, cause);
	}
}
