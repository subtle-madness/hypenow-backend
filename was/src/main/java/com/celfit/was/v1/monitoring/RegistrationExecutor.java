package com.celfit.was.v1.monitoring;

/**
 * 등록 접수(6.27) 후 백그라운드 첫 확인(monitoring 등록 호출)을 트리거하는 실행기.
 * 등록 API는 이 인터페이스에만 의존해 동기 구간(검증→행 생성→201)을 실행기 구현과 분리한다.
 * 구현은 monitoring.enabled 조건부로 정확히 하나만 뜬다 — 활성이면 {@link MonitoringRegistrationExecutor}가
 * 실제로 monitoring 서버를 호출하고, 비활성이면 {@link NoopRegistrationExecutor}가 접수 기록만 남기는
 * 폴백으로 대체한다(두 구현의 상세 조건은 각 클래스 문서 참조).
 *
 * <h2>트랜잭션 계약</h2>
 * {@code submit}은 접수 트랜잭션의 물리 커밋 **이후에만** 실행되도록 호출부가 보장한다
 * (V1MonitoringRegistrationService.register — TransactionSynchronizationManager의 afterCommit
 * 콜백으로 트리거). READ COMMITTED 격리에서 비동기 실행기가 별도 커넥션으로 pending 행을 조회하면,
 * 커밋 전에 트리거된 실행이 그 행을 아직 못 보고 빈손으로 끝나는 경합이 생기기 때문이다. 이 인터페이스를
 * 새 호출부에서 다시 배선할 때도 같은 순서(커밋 후 호출)를 지켜야 한다 — 트랜잭션이 롤백되면 애초에
 * afterCommit이 실행되지 않으므로 별도 취소 처리는 필요 없다.
 */
public interface RegistrationExecutor {

	/** registrationId에 속한 pending 항목의 첫 확인을 백그라운드로 트리거한다. 호출 시점은 위 트랜잭션 계약 참조. */
	void submit(long registrationId);
}
