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

	/** 공동구매(공구) 판정(스펙 2026-09-03) — 규칙 확정분은 즉시, 애매분만 LLM. 대상 없는 실행은
	 * 저렴한 SQL no-op이라 낮 시간 30분 간격으로 자주 돌려도 무해하다. */
	@Scheduled(cron = "${analytics.schedule.group-purchase-cron:-}")
	void groupPurchaseJudge() {
		log.info("스케줄 group-purchase-judge: {}",
				jobService.trigger(JobName.GROUP_PURCHASE_JUDGE, TriggerType.SCHEDULED));
	}
}
