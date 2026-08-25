package com.celfit.monitoring.service;

/** 이미지 백필 수동 트리거가 이미 실행 중인 백필과 겹칠 때(409) — {@link AuthorImageBackfillGuard} 획득 실패가 원인이다. */
public class AuthorImageBackfillAlreadyRunningException extends RuntimeException {

	public AuthorImageBackfillAlreadyRunningException() {
		super("이미지 백필이 이미 실행 중입니다.");
	}
}
