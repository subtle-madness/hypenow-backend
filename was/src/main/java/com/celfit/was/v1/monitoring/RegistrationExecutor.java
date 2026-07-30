package com.celfit.was.v1.monitoring;

/**
 * 등록 접수(6.27) 후 백그라운드 첫 확인(monitoring 등록 호출)을 트리거하는 실행기.
 * 등록 API는 이 인터페이스에만 의존해 동기 구간(검증→행 생성→201)을 실행기 구현과 분리한다.
 * 실제 구현(monitoring 서버 호출·비동기 실행)은 후속 태스크 — 지금은 {@link NoopRegistrationExecutor}뿐이다.
 */
public interface RegistrationExecutor {

	/** registrationId에 속한 pending 항목의 첫 확인을 백그라운드로 트리거한다. */
	void submit(long registrationId);
}
