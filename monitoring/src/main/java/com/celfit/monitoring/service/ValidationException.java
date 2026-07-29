package com.celfit.monitoring.service;

/** 요청 형식·필수 필드 위반 — 계약 §2 `VALIDATION`(400). */
public class ValidationException extends RuntimeException {

	public ValidationException(String message) {
		super(message);
	}
}
