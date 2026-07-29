package com.celfit.monitoring.service;

/** 해당 target_id 없음 — 계약 §2 `TARGET_NOT_FOUND`(404). 종결된 target은 여기 해당하지 않는다(행이 남아 있다). */
public class TargetNotFoundException extends RuntimeException {

	public TargetNotFoundException(String message) {
		super(message);
	}
}
