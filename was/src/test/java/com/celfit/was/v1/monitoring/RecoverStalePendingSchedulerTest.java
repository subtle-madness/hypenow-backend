package com.celfit.was.v1.monitoring;

import static org.mockito.BDDMockito.inOrder;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/**
 * RecoverStalePendingScheduler — item 복구(recoverStalePending)와 나이 기반 entry 확정
 * (settleStaleRegistrationEntries)의 호출 순서만 검증한다(트랙 LL §4-3 배선 순서 불변식).
 * 각 메서드 자체의 로직은 MonitoringRegistrationExecutorTest·RegistrationRepositoryTest가 덮으므로
 * 여기는 순수 Mockito로 순서만 확인한다(Spring 컨텍스트·DB 불필요).
 */
class RecoverStalePendingSchedulerTest {

	@Test
	void recover는_item_복구_후_나이_확정_순서로_호출한다() {
		// 역순이면 이번 틱에 recoverStalePending으로 살아날 수 있었던 pending item을 나이 확정이
		// 먼저 failed로 못 박아 버린다(스케줄러 클래스 문서 참조) — 순서 자체가 회귀 대상이다.
		MonitoringRegistrationExecutor executor = mock(MonitoringRegistrationExecutor.class);
		Duration timeout = Duration.ofHours(24);
		RecoverStalePendingScheduler scheduler = new RecoverStalePendingScheduler(executor, timeout);

		scheduler.recover();

		InOrder order = inOrder(executor);
		order.verify(executor).recoverStalePending();
		order.verify(executor).settleStaleRegistrationEntries(timeout);
	}
}
