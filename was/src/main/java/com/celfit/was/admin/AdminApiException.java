package com.celfit.was.admin;

/** admin 쓰기 API 표면 예외(설계 2026-07-20) — status와 message를 그대로 {"error":message}로 렌더. */
public class AdminApiException extends RuntimeException {

	private final int status;

	public AdminApiException(int status, String message) {
		super(message);
		this.status = status;
	}

	public int status() {
		return status;
	}
}
