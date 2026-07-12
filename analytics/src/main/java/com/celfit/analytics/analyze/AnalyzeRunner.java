package com.celfit.analytics.analyze;

import com.celfit.analytics.config.AnalyticsSettings;
import com.celfit.analytics.llm.SynthesisPort;
import com.celfit.analytics.llm.VisionPort;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** 분석 배치 배선 — analytics.analyze-on-startup=true일 때만 (실 API 비용). */
@Configuration
@ConditionalOnProperty(name = "analytics.analyze-on-startup", havingValue = "true")
public class AnalyzeRunner {

	@Bean
	public ContentAnalysisJob contentAnalysisJob(JdbcTemplate rawJdbcTemplate,
			@Qualifier("analysisDataSource") DataSource analysisDataSource,
			SynthesisPort synthesis, ObjectProvider<VisionPort> vision, AnalyticsSettings settings,
			@Value("${analytics.vlm-enabled:false}") boolean vlmEnabled) {
		return new ContentAnalysisJob(rawJdbcTemplate, analysisDataSource, synthesis,
				vision.getIfAvailable(), settings, vlmEnabled);
	}

	@Bean
	public CommandLineRunner analyzeOnStartup(ContentAnalysisJob job) {
		return args -> job.run();
	}
}
