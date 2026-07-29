package com.celfit.monitoring.service;

/** 해당 candidate_id 없음(또는 그 target 소속이 아님) — 계약 §2 `CANDIDATE_NOT_FOUND`(404). */
public class CandidateNotFoundException extends RuntimeException {

	public CandidateNotFoundException(String message) {
		super(message);
	}
}
