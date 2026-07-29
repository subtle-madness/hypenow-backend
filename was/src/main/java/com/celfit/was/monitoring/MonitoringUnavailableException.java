package com.celfit.was.monitoring;

/** 전송 실패(연결 불가·타임아웃·해석 불가 응답) — 같은 registrationKey로 재시도 가능(멱등 replay). */
public class MonitoringUnavailableException extends MonitoringException {

	public MonitoringUnavailableException(String message, Throwable cause) {
		super(message, cause);
	}
}
