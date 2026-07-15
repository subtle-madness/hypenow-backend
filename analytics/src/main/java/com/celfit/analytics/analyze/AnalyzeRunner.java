package com.celfit.analytics.analyze;

import com.celfit.analytics.config.AnalyticsSettings;
import com.celfit.analytics.llm.ContentAttributePort;
import com.celfit.analytics.llm.SynthesisPort;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.function.Predicate;
import javax.sql.DataSource;
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

	/**
	 * 썸네일 서명 URL 생존 확인 (인스타 CDN은 수집 후 ~4일이면 403 — 2026-07-14 실측).
	 * 만료 썸네일을 VLM에 넘기면 Anthropic 쪽 fetch가 실패하므로 호출 전에 거른다.
	 */
	public static Predicate<String> headPrecheck() {
		HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
		return url -> {
			try {
				HttpRequest req = HttpRequest.newBuilder(URI.create(url))
						.method("HEAD", HttpRequest.BodyPublishers.noBody())
						.timeout(Duration.ofSeconds(10))
						.build();
				int status = http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode();
				return status >= 200 && status < 300;
			} catch (Exception e) {
				return false;
			}
		};
	}

	@Bean
	public ContentAnalysisJob contentAnalysisJob(JdbcTemplate rawJdbcTemplate,
			@Qualifier("analysisDataSource") DataSource analysisDataSource,
			SynthesisPort synthesis, ContentAttributePort attributes, AnalyticsSettings settings,
			// vlm-enabled = 썸네일 첨부 게이트 (기본 off — 캡션 기반 5종은 항상 산출)
			@Value("${analytics.vlm-enabled:false}") boolean thumbnailEnabled) {
		return new ContentAnalysisJob(rawJdbcTemplate, analysisDataSource, synthesis,
				attributes, settings, thumbnailEnabled, headPrecheck());
	}

	@Bean
	public CommandLineRunner analyzeOnStartup(ContentAnalysisJob job) {
		return args -> job.run();
	}
}
