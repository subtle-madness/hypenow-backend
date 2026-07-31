package com.celfit.was.v1.common;

import org.springframework.http.HttpStatus;

/** v1 계약 위반을 담는 예외 — advice가 envelope로 변환한다. */
public class V1ApiException extends RuntimeException {

	private final HttpStatus status;
	private final String code;
	private final Integer retryAfterSeconds;

	public V1ApiException(HttpStatus status, String code, String message) {
		this(status, code, message, null);
	}

	/** retryAfterSeconds가 null이 아니면 advice가 Retry-After 헤더를 함께 내려보낸다. */
	public V1ApiException(HttpStatus status, String code, String message, Integer retryAfterSeconds) {
		super(message);
		this.status = status;
		this.code = code;
		this.retryAfterSeconds = retryAfterSeconds;
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

	/** 코드 지정 400 — validation()의 고정 코드(VALIDATION_FAILED)와 달리 계약 코드를 직접 든다(예: INVALID_CODE). */
	public static V1ApiException badRequest(String code, String message) {
		return new V1ApiException(HttpStatus.BAD_REQUEST, code, message);
	}

	/** 외부 의존(메일 발송 등) 실패 — 502. */
	public static V1ApiException badGateway(String code, String message) {
		return new V1ApiException(HttpStatus.BAD_GATEWAY, code, message);
	}

	public static V1ApiException rateLimited() {
		return new V1ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "요청이 너무 잦아요. 잠시 후 다시 시도해 주세요.");
	}

	/** 동시 실행 제한(ConcurrencyLimiter bulkhead) 초과 — 짧은 재시도를 유도하는 Retry-After 포함. */
	public static V1ApiException rateLimited(int retryAfterSeconds) {
		return new V1ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
				"요청이 너무 잦아요. 잠시 후 다시 시도해 주세요.", retryAfterSeconds);
	}

	public HttpStatus status() {
		return status;
	}

	public String code() {
		return code;
	}

	public Integer retryAfterSeconds() {
		return retryAfterSeconds;
	}
}
