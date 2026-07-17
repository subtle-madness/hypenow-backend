package com.celfit.analytics.admin;

import com.celfit.analytics.analyze.AccountAnalysisJob;
import com.celfit.analytics.analyze.ContentAnalysisJob;
import com.celfit.analytics.classify.CommentClassificationJob;
import com.celfit.analytics.mirror.MirrorJob;
import com.celfit.analytics.mirror.MirrorRegistry;
import com.celfit.analytics.mirror.MirrorSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.task.TaskExecutor;

/**
 * 어드민·스케줄 공용 잡 트리거 — 잡별 락으로 중복 실행 차단, 비동기 실행.
 * LLM 잡은 ObjectProvider로 실행 시점에 조회(@Lazy 빈 — 키 없으면 그 잡만 실패).
 */
public class AnalyticsJobService {

	public enum TriggerResult { ACCEPTED, BUSY }

	private static final Logger log = LoggerFactory.getLogger(AnalyticsJobService.class);

	private final JobLock lock;
	private final TaskExecutor executor;
	private final MirrorJob mirrorJob;
	private final MirrorRegistry registry;
	private final ObjectProvider<CommentClassificationJob> classifyJob;
	private final ObjectProvider<ContentAnalysisJob> analyzeJob;
	private final ObjectProvider<AccountAnalysisJob> accountAnalyzeJob;

	public AnalyticsJobService(JobLock lock, TaskExecutor executor,
			MirrorJob mirrorJob, MirrorRegistry registry,
			ObjectProvider<CommentClassificationJob> classifyJob,
			ObjectProvider<ContentAnalysisJob> analyzeJob,
			ObjectProvider<AccountAnalysisJob> accountAnalyzeJob) {
		this.lock = lock;
		this.executor = executor;
		this.mirrorJob = mirrorJob;
		this.registry = registry;
		this.classifyJob = classifyJob;
		this.analyzeJob = analyzeJob;
		this.accountAnalyzeJob = accountAnalyzeJob;
	}

	public TriggerResult trigger(JobName job, TriggerType triggerType) {
		if (!lock.tryAcquire(job)) return TriggerResult.BUSY;
		executor.execute(() -> {
			try {
				log.info("{} 시작 (trigger={})", job, triggerType);
				run(job);
			} catch (Exception e) {
				log.error("{} 잡 실패", job, e);
			} finally {
				lock.release(job);
			}
		});
		return TriggerResult.ACCEPTED;
	}

	public boolean isRunning(JobName job) {
		return lock.isRunning(job);
	}

	private void run(JobName job) {
		switch (job) {
			case MIRROR -> {
				int total = 0;
				for (MirrorSpec<?> spec : registry.specs()) {
					int rows = mirrorJob.mirror(spec);
					log.info("mirrored {} rows: {} -> {}", rows, spec.viewName(), spec.tableName());
					total += rows;
				}
				log.info("mirror complete ({} targets, {} rows)", registry.specs().size(), total);
			}
			case CLASSIFY -> classifyJob.getObject().run();
			case ANALYZE -> analyzeJob.getObject().run();
			case ACCOUNT_ANALYZE -> accountAnalyzeJob.getObject().run();
		}
	}
}
