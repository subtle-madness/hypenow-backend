package com.celfit.was.v1.brandmonitoring;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * {@link BrandDirectRegistrationExecutor#recoverStalePending()}·
 * {@link BrandDirectRegistrationExecutor#settleStaleRegistrationEntries(Duration)} 크론 배선
 * (2026-08-18 direct 통합 §T8, 레거시 {@code RecoverStalePendingScheduler}와 같은 위상) — 기본
 * 10분 간격, 독립 크론(레거시 등록 복구와 풀·스케줄 모두 분리 — MonitoringConfig의 전용 풀 참조).
 *
 * <p><b>호출 순서 불변식</b>: 같은 틱 안에서 반드시 entry 복구(recoverStalePending) → 나이 확정
 * (settleStaleRegistrationEntries) 순서로 부른다. 역순이면 이번 틱에 복구될 수 있었던 pending entry를
 * 나이 확정이 먼저 failed로 못 박아 버린다.
 */
@Component
@ConditionalOnProperty(name = "monitoring.enabled", havingValue = "true")
public class BrandDirectRegistrationRecoveryScheduler {

	private final BrandDirectRegistrationExecutor executor;
	private final Duration staleEntryTimeout;

	public BrandDirectRegistrationRecoveryScheduler(BrandDirectRegistrationExecutor executor,
			@Value("${monitoring.brand-registration.stale-entry-timeout:PT24H}") Duration staleEntryTimeout) {
		this.executor = executor;
		this.staleEntryTimeout = staleEntryTimeout;
	}

	@Scheduled(cron = "${monitoring.brand-registration.recover.cron:0 */10 * * * *}", zone = "UTC")
	public void recover() {
		executor.recoverStalePending();
		executor.settleStaleRegistrationEntries(staleEntryTimeout);
	}
}
