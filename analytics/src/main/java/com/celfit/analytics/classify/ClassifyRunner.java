package com.celfit.analytics.classify;

import com.celfit.analytics.config.AnalyticsSettings;
import com.celfit.analytics.llm.CommentClassificationPort;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** 분류 배치 배선 — analytics.classify-on-startup=true일 때만 (실 API 비용). */
@Configuration
@ConditionalOnProperty(name = "analytics.classify-on-startup", havingValue = "true")
public class ClassifyRunner {

	@Bean
	public CommentClassificationJob commentClassificationJob(JdbcTemplate rawJdbcTemplate,
			@Qualifier("analysisDataSource") DataSource analysisDataSource,
			CommentClassificationPort port, AnalyticsSettings settings) {
		return new CommentClassificationJob(rawJdbcTemplate, analysisDataSource, port, settings);
	}

	@Bean
	public CommandLineRunner classifyOnStartup(CommentClassificationJob job) {
		return args -> job.run();
	}
}
