package com.celfit.monitoring.alarm;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 알람 발송 스케줄 — 기본 "-"(비활성). 운영은 env로 5분 틱 주입(매 5분 정각 cron).
 * SweepScheduler와 같은 관용구다: 기본 비활성이라 개통 전 환경에서 실메일이 나갈 일이 없다.
 */
@Component
public class AlarmDispatchScheduler {

	private final AlarmDispatchJob job;

	public AlarmDispatchScheduler(AlarmDispatchJob job) {
		this.job = job;
	}

	@Scheduled(cron = "${monitoring.alarm.dispatch-cron:-}", zone = "UTC")
	public void dispatch() {
		job.run();
	}
}
