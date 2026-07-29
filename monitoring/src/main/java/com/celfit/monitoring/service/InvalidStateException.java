package com.celfit.monitoring.service;

/** 상태상 불가한 명령(예: 종결된 target의 후보 승인) — 계약 §2 `INVALID_STATE`(409). */
public class InvalidStateException extends RuntimeException {

	public InvalidStateException(String message) {
		super(message);
	}
}
