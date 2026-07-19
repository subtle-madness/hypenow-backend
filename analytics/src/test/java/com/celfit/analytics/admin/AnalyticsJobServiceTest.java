package com.celfit.analytics.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.celfit.analytics.analyze.AccountAnalysisJob;
import com.celfit.analytics.analyze.ContentAnalysisJob;
import com.celfit.analytics.analyze.JobResult;
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

	private AnalyticsJobService service() {
		return new AnalyticsJobService(lock, new SyncTaskExecutor(), mirrorJob, registry,
				provider(mock(CommentClassificationJob.class)), provider(analyzeJob),
				provider(mock(AccountAnalysisJob.class)));
	}

	@Test
	void 트리거는_잡을_실행하고_ACCEPTED를_반환() {
		when(analyzeJob.run()).thenReturn(new JobResult(3, 0, false));
		var result = service().trigger(JobName.ANALYZE, TriggerType.MANUAL);
		assertThat(result).isEqualTo(AnalyticsJobService.TriggerResult.ACCEPTED);
		assertThat(lock.isRunning(JobName.ANALYZE)).isFalse(); // 동기 실행 후 해제
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
				lazyProvider, provider(mock(AccountAnalysisJob.class)));
		assertThat(resolved.get()).isZero(); // 생성만으로는 미조회
		service.trigger(JobName.ANALYZE, TriggerType.MANUAL);
		assertThat(resolved.get()).isEqualTo(1);
	}
}
