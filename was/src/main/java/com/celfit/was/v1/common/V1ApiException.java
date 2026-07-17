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

	public static V1ApiException conflict(String code, String message) {
		return new V1ApiException(HttpStatus.CONFLICT, code, message);
	}

	public static V1ApiException unauthorized(String code, String message) {
		return new V1ApiException(HttpStatus.UNAUTHORIZED, code, message);
	}

	public static V1ApiException forbidden(String code, String message) {
		return new V1ApiException(HttpStatus.FORBIDDEN, code, message);
	}

	public static V1ApiException rateLimited() {
		return new V1ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "요청이 너무 잦아요. 잠시 후 다시 시도해 주세요.");
	}

	public HttpStatus status() {
		return status;
	}

	public String code() {
		return code;
	}
}
