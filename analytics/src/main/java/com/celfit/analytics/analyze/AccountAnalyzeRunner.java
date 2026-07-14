package com.celfit.analytics.analyze;

import com.celfit.analytics.config.AnalyticsSettings;
import com.celfit.analytics.llm.AccountSynthesisPort;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 계정 카피 배치 배선 — analytics.account-analyze-on-startup=true일 때만 (실 API 비용). */
@Configuration
@ConditionalOnProperty(name = "analytics.account-analyze-on-startup", havingValue = "true")
public class AccountAnalyzeRunner {

	@Bean
	public AccountAnalysisJob accountAnalysisJob(
			@Qualifier("analysisDataSource") DataSource analysisDataSource,
			AccountSynthesisPort port, AnalyticsSettings settings) {
		return new AccountAnalysisJob(analysisDataSource, port, settings);
	}

	@Bean
	public CommandLineRunner accountAnalyzeOnStartup(AccountAnalysisJob job) {
		return args -> job.run();
	}
}
