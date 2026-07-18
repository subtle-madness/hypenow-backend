package com.celfit.analytics.admin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 스케줄 트리거 골격 — analytics.schedule.enabled=true일 때만 활성 (기본 off, 크롤러 패턴).
 * 잡별 크론 미지정("-")이면 그 잡은 안 돈다. admin-enabled=true 전제(AnalyticsJobService 필요).
 */
@Component
@EnableScheduling
@ConditionalOnProperty(prefix = "analytics.schedule", name = "enabled", havingValue = "true")
public class ScheduleRunner {

	private static final Logger log = LoggerFactory.getLogger(ScheduleRunner.class);

	private final AnalyticsJobService jobService;

	public ScheduleRunner(AnalyticsJobService jobService) {
		this.jobService = jobService;
	}

	@Scheduled(cron = "${analytics.schedule.mirror-cron:-}")
	void mirror() {
		log.info("스케줄 mirror: {}", jobService.trigger(JobName.MIRROR, TriggerType.SCHEDULED));
	}

	@Scheduled(cron = "${analytics.schedule.classify-cron:-}")
	void classify() {
		log.info("스케줄 classify: {}", jobService.trigger(JobName.CLASSIFY, TriggerType.SCHEDULED));
	}

	@Scheduled(cron = "${analytics.schedule.analyze-cron:-}")
	void analyze() {
		log.info("스케줄 analyze: {}", jobService.trigger(JobName.ANALYZE, TriggerType.SCHEDULED));
	}

	@Scheduled(cron = "${analytics.schedule.account-analyze-cron:-}")
	void accountAnalyze() {
		log.info("스케줄 account-analyze: {}",
				jobService.trigger(JobName.ACCOUNT_ANALYZE, TriggerType.SCHEDULED));
	}
}
