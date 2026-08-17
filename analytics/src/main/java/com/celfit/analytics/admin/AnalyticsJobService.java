package com.celfit.analytics.admin;

import com.celfit.analytics.analyze.AccountAnalysisJob;
import com.celfit.analytics.analyze.AccountBatchCollectJob;
import com.celfit.analytics.analyze.ContentSynthesisRefreshJob;
import com.celfit.analytics.analyze.ContentAnalysisJob;
import com.celfit.analytics.analyze.JobResult;
import com.celfit.analytics.archive.ImageArchiveJob;
import com.celfit.analytics.classify.CommentClassificationJob;
import com.celfit.analytics.mirror.MirrorJob;
import com.celfit.analytics.mirror.MirrorRegistry;
import com.celfit.analytics.mirror.MirrorSpec;
import java.time.Instant;
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
	private final ObjectProvider<com.celfit.analytics.analyze.ContentBatchCollectJob> batchCollectJob;
	private final ObjectProvider<AccountBatchCollectJob> accountBatchCollectJob;
	private final ObjectProvider<AccountAnalysisJob> accountAnalyzeJob;
	private final ObjectProvider<ContentSynthesisRefreshJob> synthesisRefreshJob;
	private final ObjectProvider<ImageArchiveJob> archiveJob;
	private final ObjectProvider<com.celfit.analytics.analyze.TraitCanonJob> traitCanonJob;
	private final JobProgressRegistry progress;
	private final RunHistory history;

	public AnalyticsJobService(JobLock lock, TaskExecutor executor,
			MirrorJob mirrorJob, MirrorRegistry registry,
			ObjectProvider<CommentClassificationJob> classifyJob,
			ObjectProvider<ContentAnalysisJob> analyzeJob,
			ObjectProvider<com.celfit.analytics.analyze.ContentBatchCollectJob> batchCollectJob,
			ObjectProvider<AccountBatchCollectJob> accountBatchCollectJob,
			ObjectProvider<AccountAnalysisJob> accountAnalyzeJob,
			ObjectProvider<ContentSynthesisRefreshJob> synthesisRefreshJob,
			ObjectProvider<ImageArchiveJob> archiveJob,
			ObjectProvider<com.celfit.analytics.analyze.TraitCanonJob> traitCanonJob,
			JobProgressRegistry progress, RunHistory history) {
		this.lock = lock;
		this.executor = executor;
		this.mirrorJob = mirrorJob;
		this.registry = registry;
		this.classifyJob = classifyJob;
		this.analyzeJob = analyzeJob;
		this.batchCollectJob = batchCollectJob;
		this.accountBatchCollectJob = accountBatchCollectJob;
		this.accountAnalyzeJob = accountAnalyzeJob;
		this.synthesisRefreshJob = synthesisRefreshJob;
		this.archiveJob = archiveJob;
		this.traitCanonJob = traitCanonJob;
		this.progress = progress;
		this.history = history;
	}

	public TriggerResult trigger(JobName job, TriggerType triggerType) {
		if (!lock.tryAcquire(job)) return TriggerResult.BUSY;
		executor.execute(() -> {
			Instant startedAt = Instant.now();
			progress.start(job);
			JobResult result = null;
			Exception error = null;
			try {
				log.info("{} 시작 (trigger={})", job, triggerType);
				result = run(job);
			} catch (Exception e) {
				error = e;
				log.error("{} 잡 실패", job, e);
			} finally {
				progress.finish(job);
				lock.release(job);
				try {
					history.record(new RunHistory.Run(job, triggerType, startedAt, Instant.now(),
							outcomeOf(result, error),
							result == null ? 0 : result.processed(),
							result == null ? 0 : result.failed(),
							error == null ? null : error.getMessage()));
				} catch (Exception e) {
					log.error("{} 실행 이력 기록 실패", job, e);
				}
			}
		});
		return TriggerResult.ACCEPTED;
	}

	public boolean isRunning(JobName job) {
		return lock.isRunning(job);
	}

	static RunHistory.Outcome outcomeOf(JobResult result, Exception error) {
		if (error != null) return RunHistory.Outcome.ERROR;
		if (result.carriedOver()) return RunHistory.Outcome.QUOTA_CARRYOVER;
		return result.failed() > 0 ? RunHistory.Outcome.FAILED : RunHistory.Outcome.SUCCESS;
	}

	/**
	 * MIRROR는 뷰 단위 진행(progress.total=뷰 개수)을 보고하지만, JobResult.processed()는
	 * 단위가 달라 미러된 총 행 수를 담는다 (진행 바=뷰 개수, 이력 피드=행 수).
	 */
	private JobResult run(JobName job) {
		return switch (job) {
			case MIRROR -> {
				int totalSpecs = registry.specs().size();
				progress.reporter(JobName.MIRROR).report(0, 0, totalSpecs);
				int totalRows = 0;
				int done = 0;
				for (MirrorSpec<?> spec : registry.specs()) {
					int rows = mirrorJob.mirror(spec);
					log.info("mirrored {} rows: {} -> {}", rows, spec.viewName(), spec.tableName());
					totalRows += rows;
					done++;
					progress.reporter(JobName.MIRROR).report(done, 0, totalSpecs);
				}
				log.info("mirror complete ({} targets, {} rows)", totalSpecs, totalRows);
				yield new JobResult(totalRows, 0, false);
			}
			case CLASSIFY -> {
				int n = classifyJob.getObject().run();
				yield new JobResult(n, 0, false);
			}
			case ANALYZE -> analyzeJob.getObject().run();
			case LATE_BACKFILL_ANALYZE -> analyzeJob.getObject().runLateBackfill();
			// 수거는 종류 불문 한 트리거로 — 각 잡은 자기 pending이 없으면 no-op라 겹쳐 돌아도 무해.
			case BATCH_COLLECT -> {
				JobResult content = batchCollectJob.getObject().run();
				JobResult account = accountBatchCollectJob.getObject().run();
				yield new JobResult(content.processed() + account.processed(),
						content.failed() + account.failed(), false);
			}
			case ACCOUNT_ANALYZE -> accountAnalyzeJob.getObject().run();
			case SYNTHESIS_REFRESH -> synthesisRefreshJob.getObject().run();
			case ARCHIVE -> archiveJob.getObject().run();
			// trait 어휘 매핑 원샷(2026-07-29 스펙) — 스케줄 없음, 어드민 수동 트리거 전용
			case TRAIT_CANON_DRY -> traitCanonJob.getObject().run(true);
			case TRAIT_CANON_APPLY -> traitCanonJob.getObject().run(false);
		};
	}
}
