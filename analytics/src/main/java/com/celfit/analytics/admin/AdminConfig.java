package com.celfit.analytics.admin;

import com.celfit.analytics.analyze.AccountAnalysisJob;
import com.celfit.analytics.analyze.ContentAnalysisJob;
import com.celfit.analytics.classify.CommentClassificationJob;
import com.celfit.analytics.mirror.MirrorJob;
import com.celfit.analytics.mirror.MirrorRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;

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
	public AnalyticsJobService analyticsJobService(JobLock jobLock, TaskExecutor jobTaskExecutor,
			MirrorJob mirrorJob, MirrorRegistry mirrorRegistry,
			ObjectProvider<CommentClassificationJob> classifyJob,
			ObjectProvider<ContentAnalysisJob> analyzeJob,
			ObjectProvider<AccountAnalysisJob> accountAnalyzeJob) {
		return new AnalyticsJobService(jobLock, jobTaskExecutor, mirrorJob, mirrorRegistry,
				classifyJob, analyzeJob, accountAnalyzeJob);
	}

	@Bean(initMethod = "register", destroyMethod = "unregister")
	public LogBuffer logBuffer() {
		return new LogBuffer();
	}
}
