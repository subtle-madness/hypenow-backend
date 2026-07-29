package com.celfit.was.monitoring;

/**
 * monitoring이 에러 응답 {code, message}를 준 경우 — 어휘는 계약 §2가 정본이고 was는
 * 해석·분기 없이 그대로 담아 올린다(프론트 어휘 변환은 나중 컨트롤러 몫). 재시도 무의미.
 */
public class MonitoringApiException extends MonitoringException {

	private final String code;
	private final int httpStatus;

	public MonitoringApiException(String code, String message, int httpStatus) {
		super("[" + code + "] " + message);
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
