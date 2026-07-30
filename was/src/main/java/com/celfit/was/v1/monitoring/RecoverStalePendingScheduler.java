package com.celfit.was.v1.monitoring;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * {@link MonitoringRegistrationExecutor#recoverStalePending()} 크론 배선(갭 문서 A-1-2 곁가지) —
 * 기본 10분 간격. 다이제스트 크론(09:00 KST, {@link com.celfit.was.monitoring.DigestJob})과는
 * 별도 크론이다: 크래시 복구로 지연된 등록은 다음날 아침까지 기다릴 문제가 아니라 분 단위로
 * 재시도해야 한다(MonitoringConfig의 등록 실행기 큐 초과·프로세스 재기동 시나리오 참조).
 *
 * <p>{@code recoverStalePending()}은 이미 항목 단위로 예외를 격리한다(개별 복구 실패를
 * catch·log하고 나머지를 계속 처리) — 여기서 다시 감쌀 필요가 없다.
 */
@Component
@ConditionalOnProperty(name = "monitoring.enabled", havingValue = "true")
public class RecoverStalePendingScheduler {

	private final MonitoringRegistrationExecutor executor;

	public RecoverStalePendingScheduler(MonitoringRegistrationExecutor executor) {
		this.executor = executor;
	}

	@Scheduled(cron = "${monitoring.recover.cron:0 */10 * * * *}", zone = "UTC")
	public void recover() {
		executor.recoverStalePending();
	}
}
