package com.celfit.analytics.admin;

import com.celfit.analytics.analyze.AccountAnalysisJob;
import com.celfit.analytics.analyze.ContentAnalysisJob;
import com.celfit.analytics.analyze.ContentSynthesisRefreshJob;
import com.celfit.analytics.archive.ImageArchiveJob;
import com.celfit.analytics.classify.CommentClassificationJob;
import com.celfit.analytics.config.AnalyticsSettings;
import com.celfit.analytics.coverage.CoverageRepository;
import com.celfit.analytics.mirror.MirrorJob;
import com.celfit.analytics.mirror.MirrorRegistry;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;

/** 어드민 층 배선 — analytics.admin-enabled=true일 때만 (cloud one-shot은 false). */
@Configuration
@ConditionalOnProperty(name = "analytics.admin-enabled", havingValue = "true")
public class AdminConfig {

	@Bean
	public JobLock jobLock() {
		return new JobLock();
	}

	/** 잡 비동기 실행용 — 테스트는 SyncTaskExecutor로 대체해 결정적으로 만든다. */
	@Bean
	public TaskExecutor jobTaskExecutor() {
		return new SimpleAsyncTaskExecutor("job-");
	}

	@Bean
	public JobProgressRegistry jobProgressRegistry() {
		return new JobProgressRegistry();
	}

	/** 실행 피드 링 버퍼 — 최근 50건(07-17 결정: DB 이력 테이블 없이 인메모리로 수용). */
	@Bean
	public RunHistory runHistory() {
		return new RunHistory(50);
	}

	@Bean
	public AnalyticsJobService analyticsJobService(JobLock jobLock, TaskExecutor jobTaskExecutor,
			MirrorJob mirrorJob, MirrorRegistry mirrorRegistry,
			ObjectProvider<CommentClassificationJob> classifyJob,
			ObjectProvider<ContentAnalysisJob> analyzeJob,
			ObjectProvider<AccountAnalysisJob> accountAnalyzeJob,
			ObjectProvider<ContentSynthesisRefreshJob> synthesisRefreshJob,
			ObjectProvider<ImageArchiveJob> archiveJob,
			ObjectProvider<com.celfit.analytics.analyze.TraitCanonJob> traitCanonJob,
			JobProgressRegistry jobProgressRegistry, RunHistory runHistory) {
		return new AnalyticsJobService(jobLock, jobTaskExecutor, mirrorJob, mirrorRegistry,
				classifyJob, analyzeJob, accountAnalyzeJob, synthesisRefreshJob, archiveJob,
				traitCanonJob, jobProgressRegistry, runHistory);
	}

	@Bean(initMethod = "register", destroyMethod = "unregister")
	public LogBuffer logBuffer() {
		return new LogBuffer();
	}

	/**
	 * 스케줄 가시화 — 생성자 @Value는 직접 호출 시 무시되므로 @Bean 메서드 파라미터로 값을 주입해 전달.
	 * ScheduleRunner와 같은 프로퍼티 키를 읽는다(잡별 다음 발화 KST).
	 */
	@Bean
	public ScheduleInfo scheduleInfo(
			@Value("${analytics.schedule.enabled:false}") boolean enabled,
			@Value("${analytics.schedule.mirror-cron:-}") String mirrorCron,
			@Value("${analytics.schedule.classify-cron:-}") String classifyCron,
			@Value("${analytics.schedule.analyze-cron:-}") String analyzeCron,
			@Value("${analytics.schedule.account-analyze-cron:-}") String accountCron,
			@Value("${analytics.schedule.archive-cron:-}") String archiveCron) {
		return new ScheduleInfo(enabled, mirrorCron, classifyCron, analyzeCron, accountCron, archiveCron);
	}

	@Bean
	public PipelineStatsService pipelineStatsService(JdbcTemplate rawJdbcTemplate,
			@Qualifier("analysisDataSource") DataSource analysisDataSource, AnalyticsSettings settings) {
		return new PipelineStatsService(rawJdbcTemplate, analysisDataSource, settings);
	}

	@Bean
	public CoverageRepository coverageRepository(JdbcTemplate rawJdbcTemplate,
			@Qualifier("analysisDataSource") DataSource analysisDataSource) {
		return new CoverageRepository(rawJdbcTemplate, analysisDataSource);
	}
}
