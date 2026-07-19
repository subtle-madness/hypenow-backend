package com.celfit.analytics.admin;

import com.celfit.analytics.analyze.AccountAnalysisJob;
import com.celfit.analytics.analyze.ContentAnalysisJob;
import com.celfit.analytics.classify.CommentClassificationJob;
import com.celfit.analytics.config.AnalyticsSettings;
import com.celfit.analytics.mirror.MirrorJob;
import com.celfit.analytics.mirror.MirrorRegistry;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
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
			JobProgressRegistry jobProgressRegistry, RunHistory runHistory) {
		return new AnalyticsJobService(jobLock, jobTaskExecutor, mirrorJob, mirrorRegistry,
				classifyJob, analyzeJob, accountAnalyzeJob, jobProgressRegistry, runHistory);
	}

	@Bean(initMethod = "register", destroyMethod = "unregister")
	public LogBuffer logBuffer() {
		return new LogBuffer();
	}

	@Bean
	public JobCostEstimator jobCostEstimator(JdbcTemplate rawJdbcTemplate,
			@Qualifier("analysisDataSource") DataSource analysisDataSource, AnalyticsSettings settings) {
		return new JobCostEstimator(rawJdbcTemplate, analysisDataSource, settings);
	}
}
