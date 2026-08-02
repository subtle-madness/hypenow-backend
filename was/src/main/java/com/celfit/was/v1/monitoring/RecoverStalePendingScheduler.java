package com.celfit.was.v1.monitoring;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * {@link MonitoringRegistrationExecutor#recoverStalePending()}·
 * {@link MonitoringRegistrationExecutor#settleStaleRegistrationEntries(Duration)} 크론 배선(갭 문서
 * A-1-2 곁가지 + 트랙 LL §4-3) — 기본 10분 간격. 다이제스트 크론(09:00 KST,
 * {@link com.celfit.was.monitoring.DigestJob})과는 별도 크론이다: 크래시 복구로 지연된 등록은
 * 다음날 아침까지 기다릴 문제가 아니라 분 단위로 재시도해야 한다(MonitoringConfig의 등록 실행기
 * 큐 초과·프로세스 재기동 시나리오 참조).
 *
 * <p>{@code recoverStalePending()}은 이미 항목 단위로 예외를 격리한다(개별 복구 실패를
 * catch·log하고 나머지를 계속 처리) — 여기서 다시 감쌀 필요가 없다.
 *
 * <p><b>호출 순서 불변식</b>: 같은 틱 안에서 반드시 item 복구(recoverStalePending) → 나이 확정
 * (settleStaleRegistrationEntries) 순서로 부른다(settleStaleRegistrationEntries 클래스 문서 참조).
 * 역순이면 이번 틱에 복구될 수 있었던 pending item을 나이 확정이 먼저 failed로 못 박아 버린다.
 */
@Component
@ConditionalOnProperty(name = "monitoring.enabled", havingValue = "true")
public class RecoverStalePendingScheduler {

	private final MonitoringRegistrationExecutor executor;
	private final Duration staleEntryTimeout;

	public RecoverStalePendingScheduler(MonitoringRegistrationExecutor executor,
			@Value("${monitoring.registration.stale-entry-timeout:PT24H}") Duration staleEntryTimeout) {
		this.executor = executor;
		this.staleEntryTimeout = staleEntryTimeout;
	}

	@Scheduled(cron = "${monitoring.recover.cron:0 */10 * * * *}", zone = "UTC")
	public void recover() {
		executor.recoverStalePending();
		executor.settleStaleRegistrationEntries(staleEntryTimeout);
	}
}
