package com.celfit.monitoring.service;

import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 열거 실패 재시도 스케줄(2026-09, 결함 1) — 기본 5분 주기(고정 지연,
 * {@code monitoring.brand.backfill-retry.interval}). 킬 스위치
 * {@code monitoring.brand.backfill-retry.enabled}(기본 true)가 false면 이 빈 자체가 등록되지
 * 않는다({@link ConditionalOnProperty}) — {@link BrandRegistrationService}가 같은 키를 봐서
 * 즉시 실패 문구를 exhausted로 맞추는 것과 짝이다.
 *
 * <p>가드 2단 — ① 자체 {@code AtomicBoolean}으로 이전 틱이 아직 안 끝났으면(재시도 executor가
 * 밀려 있는 등) 이번 틱을 스킵한다(캠페인 SweepGuard와 같은 CAS 근거). ② {@link BrandSweepGuard}가
 * 잡혀 있으면(= 02:00 야간 브랜드 스윕 진행 중) 틱 전체를 스킵한다 — 스윕과 같은 브랜드에
 * {@code sweepCore}가 겹쳐 도는 것과, 스윕의 전역 Hiker 콜 예산이 재시도와 경합하는 것을 막는다.
 */
@Component
@ConditionalOnProperty(prefix = "monitoring.brand.backfill-retry", name = "enabled",
		havingValue = "true", matchIfMissing = true)
public class BrandBackfillRetryScheduler {

	private static final Logger log = LoggerFactory.getLogger(BrandBackfillRetryScheduler.class);

	private final BrandBackfillRetryJob job;
	private final BrandSweepGuard sweepGuard;
	private final AtomicBoolean running = new AtomicBoolean(false);

	public BrandBackfillRetryScheduler(BrandBackfillRetryJob job, BrandSweepGuard sweepGuard) {
		this.job = job;
		this.sweepGuard = sweepGuard;
	}

	@Scheduled(fixedDelayString = "${monitoring.brand.backfill-retry.interval:5m}")
	public void tick() {
		if (sweepGuard.isRunning()) {
			log.info("브랜드 백필 재시도 스킵 — 야간 스윕 진행 중");
			return;
		}
		if (!running.compareAndSet(false, true)) {
			log.warn("브랜드 백필 재시도 스킵 — 이전 틱 미종료");
			return;
		}
		try {
			job.run();
		} finally {
			running.set(false);
		}
	}
}
