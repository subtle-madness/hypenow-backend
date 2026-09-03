package com.celfit.analytics.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.celfit.analytics.analyze.AccountAnalysisJob;
import com.celfit.analytics.analyze.AccountBatchCollectJob;
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
	private final com.celfit.analytics.analyze.ContentBatchCollectJob contentBatchCollectJob =
			mock(com.celfit.analytics.analyze.ContentBatchCollectJob.class);
	private final AccountBatchCollectJob accountBatchCollectJob = mock(AccountBatchCollectJob.class);
	private final JobProgressRegistry progress = new JobProgressRegistry();
	private final RunHistory history = new RunHistory(50);
	private final com.celfit.analytics.mirror.DerivedViewRefresher derivedViewRefresher =
			mock(com.celfit.analytics.mirror.DerivedViewRefresher.class);

	private AnalyticsJobService service() {
		return new AnalyticsJobService(lock, new SyncTaskExecutor(), mirrorJob, registry,
				provider(mock(CommentClassificationJob.class)), provider(analyzeJob),
				provider(contentBatchCollectJob),
				provider(accountBatchCollectJob),
				provider(mock(AccountAnalysisJob.class)),
				provider(mock(com.celfit.analytics.analyze.ContentSynthesisRefreshJob.class)),
				provider(archiveJob),
				provider(mock(com.celfit.analytics.analyze.TraitCanonJob.class)),
				progress, history, derivedViewRefresher);
	}

	@Test
	void 입력_변경_잡_성공_후_파생_matview를_갱신한다() {
		when(analyzeJob.run()).thenReturn(new JobResult(1, 0, false));
		service().trigger(JobName.ANALYZE, TriggerType.MANUAL);
		org.mockito.Mockito.verify(derivedViewRefresher).refresh();
	}

	@Test
	void 입력_무관_잡은_파생_matview를_갱신하지_않는다() {
		when(archiveJob.run()).thenReturn(new JobResult(1, 0, false));
		service().trigger(JobName.ARCHIVE, TriggerType.MANUAL);
		org.mockito.Mockito.verify(derivedViewRefresher, org.mockito.Mockito.never()).refresh();
	}

	@Test
	void 갱신_실패는_잡_결과를_오염시키지_않는다() {
		when(analyzeJob.run()).thenReturn(new JobResult(1, 0, false));
		org.mockito.Mockito.doThrow(new RuntimeException("boom")).when(derivedViewRefresher).refresh();
		service().trigger(JobName.ANALYZE, TriggerType.MANUAL);
		assertThat(history.recent(1).getFirst().outcome()).isEqualTo(RunHistory.Outcome.SUCCESS);
	}

	@Test
	void 트리거는_잡을_실행하고_ACCEPTED를_반환() {
		when(analyzeJob.run()).thenReturn(new JobResult(3, 0, false));
		var result = service().trigger(JobName.ANALYZE, TriggerType.MANUAL);
		assertThat(result).isEqualTo(AnalyticsJobService.TriggerResult.ACCEPTED);
		assertThat(lock.isRunning(JobName.ANALYZE)).isFalse(); // 동기 실행 후 해제
	}

	@Test
	void fact_analyze_잡을_트리거하면_runFacts가_호출된다() {
		when(analyzeJob.runFacts()).thenReturn(new JobResult(1, 0, false));
		var result = service().trigger(JobName.FACT_ANALYZE, TriggerType.MANUAL);

		assertThat(result).isEqualTo(AnalyticsJobService.TriggerResult.ACCEPTED);
		var run = history.recent(1).getFirst();
		assertThat(run.job()).isEqualTo(JobName.FACT_ANALYZE);
		assertThat(run.processed()).isEqualTo(1);
	}

	@Test
	void fact_analyze도_파생_matview_갱신_대상이다() {
		// 파트 A가 채우는 사실 컬럼(is_beauty·main_category·ad_type)이 발굴 사전집계 MV의 입력이다 -
		// 온라인 폴백 경로에서 수거 잡을 안 타므로 이 잡 자체가 갱신 후크를 가져야 한다.
		when(analyzeJob.runFacts()).thenReturn(new JobResult(1, 0, false));
		service().trigger(JobName.FACT_ANALYZE, TriggerType.MANUAL);

		org.mockito.Mockito.verify(derivedViewRefresher).refresh();
	}

	@Test
	void fact_analyze가_처리0건_no_op이면_파생_matview를_갱신하지_않는다() {
		// unified 모드 no-op(또는 split인데 대상 없음)은 입력이 실제로 안 바뀐 것 - 매 무의미한
		// 트리거마다 무거운 matview 재계산을 태울 이유가 없다(2026-09-03 리뷰).
		when(analyzeJob.runFacts()).thenReturn(new JobResult(0, 0, false));
		service().trigger(JobName.FACT_ANALYZE, TriggerType.MANUAL);

		org.mockito.Mockito.verify(derivedViewRefresher, org.mockito.Mockito.never()).refresh();
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
				lazyProvider, provider(mock(com.celfit.analytics.analyze.ContentBatchCollectJob.class)),
				provider(mock(AccountBatchCollectJob.class)),
				provider(mock(AccountAnalysisJob.class)),
				provider(mock(com.celfit.analytics.analyze.ContentSynthesisRefreshJob.class)),
				provider(mock(ImageArchiveJob.class)),
				provider(mock(com.celfit.analytics.analyze.TraitCanonJob.class)), progress, history,
				derivedViewRefresher);
		assertThat(resolved.get()).isZero(); // 생성만으로는 미조회
		service.trigger(JobName.ANALYZE, TriggerType.MANUAL);
		assertThat(resolved.get()).isEqualTo(1);
	}

	@Test
	void batch_collect_트리거하면_콘텐츠와_계정_수거_결과를_합산() {
		when(contentBatchCollectJob.run()).thenReturn(new JobResult(3, 1, false));
		when(accountBatchCollectJob.run()).thenReturn(new JobResult(2, 0, false));
		var result = service().trigger(JobName.BATCH_COLLECT, TriggerType.MANUAL);
		assertThat(result).isEqualTo(AnalyticsJobService.TriggerResult.ACCEPTED);
		var run = history.recent(1).getFirst();
		assertThat(run.job()).isEqualTo(JobName.BATCH_COLLECT);
		assertThat(run.processed()).isEqualTo(5);
		assertThat(run.failed()).isEqualTo(1);
	}

	/** 부분 성공 보존(최종 리뷰 M-4) — 콘텐츠 수거가 예외를 던져도 계정 수거는 실행되고 결과가
	 *  반영돼야 한다(한쪽 최상단 예외가 다른 쪽을 막지 않는 격리). */
	@Test
	void 콘텐츠_수거가_던져도_계정_수거는_실행된다() {
		when(contentBatchCollectJob.run()).thenThrow(new IllegalStateException("db 순단"));
		when(accountBatchCollectJob.run()).thenReturn(new JobResult(2, 0, false));
		var result = service().trigger(JobName.BATCH_COLLECT, TriggerType.MANUAL);
		assertThat(result).isEqualTo(AnalyticsJobService.TriggerResult.ACCEPTED);
		var run = history.recent(1).getFirst();
		assertThat(run.job()).isEqualTo(JobName.BATCH_COLLECT);
		// 콘텐츠는 예외 → processed 0·failed 1로 집계, 계정은 정상 2건 보존 → 합산 processed=2, failed=1
		assertThat(run.processed()).isEqualTo(2);
		assertThat(run.failed()).isEqualTo(1);
		assertThat(run.outcome()).isEqualTo(RunHistory.Outcome.FAILED); // failed>0
	}
}
