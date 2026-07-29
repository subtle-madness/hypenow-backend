package com.celfit.monitoring.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 일일 스윕 스케줄 — 기본 "-"(비활성). 운영은 env로 UTC 크론 주입(KST 02:00 = 0 0 17 * * *). */
@Component
public class SweepScheduler {

	private final DailySweepJob job;

	public SweepScheduler(DailySweepJob job) {
		this.job = job;
	}

	@Scheduled(cron = "${monitoring.schedule.sweep-cron:-}", zone = "UTC")
	public void sweep() {
		job.run();
	}
}
