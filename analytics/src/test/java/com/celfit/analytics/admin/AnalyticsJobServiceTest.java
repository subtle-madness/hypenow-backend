package com.celfit.analytics.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.celfit.analytics.analyze.AccountAnalysisJob;
import com.celfit.analytics.analyze.ContentAnalysisJob;
import com.celfit.analytics.analyze.JobResult;
import com.celfit.analytics.archive.ImageArchiveJob;
import com.celfit.analytics.classify.CommentClassificationJob;
import com.celfit.analytics.mirror.MirrorJob;
import com.celfit.analytics.mirror.MirrorRegistry;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.task.SyncTaskExecutor;

class AnalyticsJobServiceTest {

	private static <T> ObjectProvider<T> provider(T instance) {
		return new ObjectProvider<>() {
			@Override
			public T getObject() {
				return instance;
			}
		};
	}

	private final JobLock lock = new JobLock();
	private final MirrorJob mirrorJob = mock(MirrorJob.class);
	private final MirrorRegistry registry = new MirrorRegistry(List.of());
	private final ContentAnalysisJob analyzeJob = mock(ContentAnalysisJob.class);
	private final ImageArchiveJob archiveJob = mock(ImageArchiveJob.class);
	private final JobProgressRegistry progress = new JobProgressRegistry();
	private final RunHistory history = new RunHistory(50);

	private AnalyticsJobService service() {
		return new AnalyticsJobService(lock, new SyncTaskExecutor(), mirrorJob, registry,
				provider(mock(CommentClassificationJob.class)), provider(analyzeJob),
				provider(mock(AccountAnalysisJob.class)),
				provider(mock(com.celfit.analytics.analyze.ContentSynthesisRefreshJob.class)),
				provider(archiveJob),
				progress, history);
	}

	@Test
	void 트리거는_잡을_실행하고_ACCEPTED를_반환() {
		when(analyzeJob.run()).thenReturn(new JobResult(3, 0, false));
		var result = service().trigger(JobName.ANALYZE, TriggerType.MANUAL);
		assertThat(result).isEqualTo(AnalyticsJobService.TriggerResult.ACCEPTED);
		assertThat(lock.isRunning(JobName.ANALYZE)).isFalse(); // 동기 실행 후 해제
	}

	@Test
	void late_backfill_잡을_트리거하면_runLateBackfill이_호출된다() {
		when(analyzeJob.runLateBackfill()).thenReturn(new JobResult(2, 0, false));
		var result = service().trigger(JobName.LATE_BACKFILL_ANALYZE, TriggerType.MANUAL);
		assertThat(result).isEqualTo(AnalyticsJobService.TriggerResult.ACCEPTED);
		var run = history.recent(1).getFirst();
		assertThat(run.job()).isEqualTo(JobName.LATE_BACKFILL_ANALYZE);
		assertThat(run.processed()).isEqualTo(2);
	}

	@Test
	void archive_잡을_트리거하면_run이_호출된다() {
		when(archiveJob.run()).thenReturn(new JobResult(3, 1, false));
		var result = service().trigger(JobName.ARCHIVE, TriggerType.MANUAL);
		assertThat(result).isEqualTo(AnalyticsJobService.TriggerResult.ACCEPTED);
		var run = history.recent(1).getFirst();
		assertThat(run.job()).isEqualTo(JobName.ARCHIVE);
		assertThat(run.processed()).isEqualTo(3);
		assertThat(run.failed()).isEqualTo(1);
	}

	@Test
	void 실행_중이면_BUSY() {
		lock.tryAcquire(JobName.MIRROR);
		var result = service().trigger(JobName.MIRROR, TriggerType.MANUAL);
		assertThat(result).isEqualTo(AnalyticsJobService.TriggerResult.BUSY);
	}

	@Test
	void 잡이_예외를_던져도_락은_해제() {
		when(analyzeJob.run()).thenThrow(new IllegalStateException("boom"));
		service().trigger(JobName.ANALYZE, TriggerType.MANUAL);
		assertThat(lock.isRunning(JobName.ANALYZE)).isFalse();
	}

	@Test
	void 성공하면_이력에_SUCCESS로_기록() {
		when(analyzeJob.run()).thenReturn(new JobResult(3, 0, false));
		service().trigger(JobName.ANALYZE, TriggerType.MANUAL);
		var run = history.recent(1).getFirst();
		assertThat(run.job()).isEqualTo(JobName.ANALYZE);
		assertThat(run.outcome()).isEqualTo(RunHistory.Outcome.SUCCESS);
		assertThat(run.processed()).isEqualTo(3);
	}

	@Test
	void 예외가_나면_이력에_ERROR와_메시지_기록() {
		when(analyzeJob.run()).thenThrow(new IllegalStateException("boom"));
		service().trigger(JobName.ANALYZE, TriggerType.MANUAL);
		var run = history.recent(1).getFirst();
		assertThat(run.outcome()).isEqualTo(RunHistory.Outcome.ERROR);
		assertThat(run.note()).isEqualTo("boom");
	}

	@Test
	void outcomeOf_이월이면_QUOTA_CARRYOVER() {
		var outcome = AnalyticsJobService.outcomeOf(new JobResult(1, 0, true), null);
		assertThat(outcome).isEqualTo(RunHistory.Outcome.QUOTA_CARRYOVER);
	}

	@Test
	void outcomeOf_실패_건이_있으면_FAILED() {
		var outcome = AnalyticsJobService.outcomeOf(new JobResult(5, 2, false), null);
		assertThat(outcome).isEqualTo(RunHistory.Outcome.FAILED);
	}

	@Test
	void 지연_잡_공급자는_실행_시점에만_조회() {
		AtomicInteger resolved = new AtomicInteger();
		ObjectProvider<ContentAnalysisJob> lazyProvider = new ObjectProvider<>() {
			@Override
			public ContentAnalysisJob getObject() {
				resolved.incrementAndGet();
				return analyzeJob;
			}
		};
		AnalyticsJobService service = new AnalyticsJobService(lock, new SyncTaskExecutor(),
				mirrorJob, registry, provider(mock(CommentClassificationJob.class)),
				lazyProvider, provider(mock(AccountAnalysisJob.class)),
				provider(mock(com.celfit.analytics.analyze.ContentSynthesisRefreshJob.class)),
				provider(mock(ImageArchiveJob.class)), progress, history);
		assertThat(resolved.get()).isZero(); // 생성만으로는 미조회
		service.trigger(JobName.ANALYZE, TriggerType.MANUAL);
		assertThat(resolved.get()).isEqualTo(1);
	}
}
