package com.celfit.was.monitoring;

/**
 * monitoring이 에러 응답 {code, message}를 준 경우 — 어휘는 계약 §2가 정본이고 클라이언트는
 * 해석·분기 없이 그대로 담아 올린다. 프론트 어휘 변환은 V1ExceptionAdvice.handleMonitoringApi가
 * httpStatus 기준으로 수행한다(등록·share 해소 경로는 그 전에 로컬에서 잡아 처리 내역으로 흡수).
 * 재시도 무의미.
 */
public class MonitoringApiException extends MonitoringException {

	private final String code;
	private final int httpStatus;

	public MonitoringApiException(String code, String message, int httpStatus) {
		this(code, message, httpStatus, null);
	}

	public MonitoringApiException(String code, String message, int httpStatus, Throwable cause) {
		super("[" + code + "] " + message, cause);
		this.code = code;
		this.httpStatus = httpStatus;
	}

	public String code() {
		return code;
	}

	public int httpStatus() {
		return httpStatus;
	}
}
