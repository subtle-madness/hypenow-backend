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

	/** 파트 A(사실) 배치 — 2026-09-03 2단계 분리. analytics.analyze-mode=unified면 잡이 no-op이라
	 * 크론이 돌아도 로그 한 줄만 남는다(토글 전 무해). */
	@Scheduled(cron = "${analytics.schedule.fact-analyze-cron:-}")
	void factAnalyze() {
		log.info("스케줄 fact-analyze: {}", jobService.trigger(JobName.FACT_ANALYZE, TriggerType.SCHEDULED));
	}

	@Scheduled(cron = "${analytics.schedule.late-backfill-analyze-cron:-}")
	void lateBackfillAnalyze() {
		log.info("스케줄 late-backfill-analyze: {}",
				jobService.trigger(JobName.LATE_BACKFILL_ANALYZE, TriggerType.SCHEDULED));
	}

	@Scheduled(cron = "${analytics.schedule.account-analyze-cron:-}")
	void accountAnalyze() {
		log.info("스케줄 account-analyze: {}",
				jobService.trigger(JobName.ACCOUNT_ANALYZE, TriggerType.SCHEDULED));
	}

	@Scheduled(cron = "${analytics.schedule.archive-cron:-}")
	void archive() {
		log.info("스케줄 archive: {}", jobService.trigger(JobName.ARCHIVE, TriggerType.SCHEDULED));
	}

	/** 배치 전송(2026-08-11) 수거 — analytics.analyze-transport=batch일 때 전날 밤 제출분을 회수한다.
	 * transport=online이면 content_batch_jobs에 pending 행이 없어 매 호출이 사실상 no-op이다. */
	@Scheduled(cron = "${analytics.schedule.batch-collect-cron:-}")
	void batchCollect() {
		log.info("스케줄 batch-collect: {}", jobService.trigger(JobName.BATCH_COLLECT, TriggerType.SCHEDULED));
	}
}
