package com.celfit.analytics.admin;

import com.celfit.analytics.analyze.AccountAnalysisJob;
import com.celfit.analytics.analyze.AccountBatchCollectJob;
import com.celfit.analytics.analyze.ContentSynthesisRefreshJob;
import com.celfit.analytics.analyze.ContentAnalysisJob;
import com.celfit.analytics.analyze.JobResult;
import com.celfit.analytics.archive.ImageArchiveJob;
import com.celfit.analytics.classify.CommentClassificationJob;
import com.celfit.analytics.mirror.DerivedViewRefresher;
import com.celfit.analytics.mirror.MirrorJob;
import com.celfit.analytics.mirror.MirrorRegistry;
import com.celfit.analytics.mirror.MirrorSpec;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
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
	private final DerivedViewRefresher derivedViewRefresher;

	/** 파생 matview 입력(account_content_series·content_analyses)을 쓰는 잡 - 완료 후 사전집계를 갱신한다.
	 *  FACT_ANALYZE(2026-09-03)는 사실 컬럼(is_beauty·main_category·ad_type)을 채우므로 같은 대상이다 -
	 *  배치 경로에서는 BATCH_COLLECT가 갱신하지만 온라인 폴백 경로에는 이 후크뿐이다. */
	private static final Set<JobName> DERIVED_INPUT_JOBS = EnumSet.of(
			JobName.MIRROR, JobName.ANALYZE, JobName.FACT_ANALYZE,
			JobName.LATE_BACKFILL_ANALYZE, JobName.BATCH_COLLECT);

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
			JobProgressRegistry progress, RunHistory history,
			DerivedViewRefresher derivedViewRefresher) {
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
		this.derivedViewRefresher = derivedViewRefresher;
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
				refreshDerivedViews(job, result);
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

	/** 입력 변경 잡 성공(부분 실패 포함) 후 발굴 사전집계 matview 갱신 — 실패해도 잡 이력은 오염시키지 않는다
	 * (다음 입력 잡 후크가 재시도 기회). run()이 던지면 호출 자체가 스킵된다.
	 *
	 * <p>FACT_ANALYZE가 처리 0건(unified 모드 no-op 또는 split인데 대상 없음)이면 갱신을 건너뛴다
	 * (2026-09-03 리뷰) — 입력이 실제로 안 바뀌었는데도 매 무의미한 트리거마다 무거운 matview
	 * 재계산을 태울 이유가 없다. 다른 DERIVED_INPUT_JOBS 멤버는 processed=0이어도 그대로 갱신한다 -
	 * 이 예외는 FACT_ANALYZE의 흔한 no-op(토글 off) 특성에 한정한다. */
	private void refreshDerivedViews(JobName job, JobResult result) {
		if (!DERIVED_INPUT_JOBS.contains(job)) return;
		if (job == JobName.FACT_ANALYZE && result.processed() == 0) return;
		try {
			derivedViewRefresher.refresh();
		} catch (Exception e) {
			log.error("파생 matview 갱신 실패", e);
		}
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
			// 파트 A(사실) - 같은 빈의 다른 진입점. analyze-mode=unified면 잡 안에서 no-op이다.
			case FACT_ANALYZE -> analyzeJob.getObject().runFacts();
			case LATE_BACKFILL_ANALYZE -> analyzeJob.getObject().runLateBackfill();
			// 수거는 종류 불문 한 트리거로 — 각 잡은 자기 pending이 없으면 no-op라 겹쳐 돌아도 무해.
			// 두 수거 잡을 각각 safeCollect로 격리한다 — 콘텐츠 수거의 최상단 예외(DB 순단 등)가
			// 계정 수거를 막지 않게 한다(최종 리뷰 M-4). 실패한 쪽은 log.error+failed 1 가산,
			// 성공한 쪽 결과는 그대로 보존한다.
			case BATCH_COLLECT -> {
				JobResult content = safeCollect("콘텐츠", () -> batchCollectJob.getObject().run());
				JobResult account = safeCollect("계정", () -> accountBatchCollectJob.getObject().run());
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

	/**
	 * BATCH_COLLECT 안에서 콘텐츠·계정 수거 잡을 각각 격리 실행한다(최종 리뷰 M-4) — 한쪽이 던진
	 * 예외(예: DB 순단으로 콘텐츠 수거의 pending 조회 자체가 실패)가 다른 쪽 수거를 막지 않게 한다.
	 * 실패하면 처리 0·실패 1로 집계하고 다음 BATCH_COLLECT 사이클이 자연 재시도한다.
	 */
	private JobResult safeCollect(String label, java.util.function.Supplier<JobResult> action) {
		try {
			return action.get();
		} catch (Exception e) {
			log.error("{} 배치 수거 실패 — 다음 BATCH_COLLECT 사이클에서 재시도", label, e);
			return new JobResult(0, 1, false);
		}
	}
}
