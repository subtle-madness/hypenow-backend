package com.celfit.monitoring.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 일일 스윕 스케줄 — 기본 "-"(비활성). 운영은 env로 UTC 크론 주입(KST 02:00 = 0 0 17 * * *).
 *
 * <p>{@link SweepGuard}를 수동 트리거(SweepCommandService)와 공유한다 — 크론이 도는 도중 수동
 * 트리거가 들어오거나 그 반대인 경우 모두 이 하나의 가드로 막는다. 크론 실행 시점에 이미 실행
 * 중이면(가드 획득 실패) 이번 틱은 건너뛴다 — 다음날 크론이 자연 재시도한다.
 */
@Component
public class SweepScheduler {

	private static final Logger log = LoggerFactory.getLogger(SweepScheduler.class);

	private final DailySweepJob job;
	private final SweepGuard guard;

	public SweepScheduler(DailySweepJob job, SweepGuard guard) {
		this.job = job;
		this.guard = guard;
	}

	@Scheduled(cron = "${monitoring.schedule.sweep-cron:-}", zone = "UTC")
	public void sweep() {
		if (!guard.tryAcquire()) {
			log.warn("크론 스윕 스킵 — 이미 실행 중(수동 트리거 또는 이전 스윕 미종료)");
			return;
		}
		try {
			job.run();
		} finally {
			guard.release();
		}
	}
}
