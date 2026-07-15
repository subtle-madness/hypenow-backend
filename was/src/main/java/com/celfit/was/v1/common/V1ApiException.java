package com.celfit.was.v1.common;

import org.springframework.http.HttpStatus;

/** v1 계약 위반을 담는 예외 — advice가 envelope로 변환한다. */
public class V1ApiException extends RuntimeException {

	private final HttpStatus status;
	private final String code;

	public V1ApiException(HttpStatus status, String code, String message) {
		super(message);
		this.status = status;
		this.code = code;
	}

	public static V1ApiException notFound(String message) {
		return new V1ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", message);
	}

	public static V1ApiException validation(String message) {
		return new V1ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", message);
	}

	public HttpStatus status() {
		return status;
	}

	public String code() {
		return code;
	}
}
