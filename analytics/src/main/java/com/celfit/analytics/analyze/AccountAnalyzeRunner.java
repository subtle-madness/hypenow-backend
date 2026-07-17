package com.celfit.analytics.analyze;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 계정 카피 배치 기동 트리거 — analytics.account-analyze-on-startup=true일 때만 (실 API 비용). 잡 빈은 JobConfig. */
@Configuration
@ConditionalOnProperty(name = "analytics.account-analyze-on-startup", havingValue = "true")
public class AccountAnalyzeRunner {

	@Bean
	public CommandLineRunner accountAnalyzeOnStartup(AccountAnalysisJob job) {
		return args -> job.run();
	}
}
