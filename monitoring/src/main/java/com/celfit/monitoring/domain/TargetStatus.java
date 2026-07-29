package com.celfit.monitoring.domain;

/** 모니터링 대상의 생애주기 상태 — WATCHING·TRACKING만 스윕 대상(active). */
public enum TargetStatus {
	WATCHING, TRACKING, EXPIRED, CANCELED, FAILED;

	public boolean active() {
		return this == WATCHING || this == TRACKING;
	}
}
