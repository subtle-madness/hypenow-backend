package com.celfit.analytics.classify;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 분류 배치 기동 트리거 — analytics.classify-on-startup=true일 때만 (실 API 비용). 잡 빈은 JobConfig. */
@Configuration
@ConditionalOnProperty(name = "analytics.classify-on-startup", havingValue = "true")
public class ClassifyRunner {

	@Bean
	public CommandLineRunner classifyOnStartup(CommentClassificationJob job) {
		return args -> job.run();
	}
}
