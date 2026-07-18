package com.celfit.analytics.config;

import com.celfit.analytics.analyze.AccountAnalysisJob;
import com.celfit.analytics.analyze.ContentAnalysisJob;
import com.celfit.analytics.classify.CommentClassificationJob;
import com.celfit.analytics.llm.AccountSynthesisPort;
import com.celfit.analytics.llm.CommentClassificationPort;
import com.celfit.analytics.llm.ContentInsightPort;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.function.Predicate;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * LLM 잡 빈 배선 — one-shot 러너(*-on-startup)와 어드민(admin-enabled) 양쪽이 쓴다.
 * 전부 @Lazy: 어드민 모드에서 서버 기동 시 Anthropic 키가 없어도 뜨고,
 * 첫 트리거 때 포트→클라이언트 체인이 생성된다(키 없으면 그 잡만 실패 — 로그 패널에 노출).
 */
@Configuration
public class JobConfig {

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
	@Lazy
	@ConditionalOnExpression("${analytics.classify-on-startup:false} or ${analytics.admin-enabled:false}")
	public CommentClassificationJob commentClassificationJob(JdbcTemplate rawJdbcTemplate,
			@Qualifier("analysisDataSource") DataSource analysisDataSource,
			CommentClassificationPort port, AnalyticsSettings settings) {
		return new CommentClassificationJob(rawJdbcTemplate, analysisDataSource, port, settings);
	}

	@Bean
	@Lazy
	@ConditionalOnExpression("${analytics.analyze-on-startup:false} or ${analytics.admin-enabled:false}")
	public ContentAnalysisJob contentAnalysisJob(JdbcTemplate rawJdbcTemplate,
			@Qualifier("analysisDataSource") DataSource analysisDataSource,
			ContentInsightPort insight, AnalyticsSettings settings,
			// vlm-enabled = 썸네일 첨부 게이트 (기본 off — 캡션 기반 5종은 항상 산출)
			@Value("${analytics.vlm-enabled:false}") boolean thumbnailEnabled) {
		return new ContentAnalysisJob(rawJdbcTemplate, analysisDataSource, insight,
				settings, thumbnailEnabled, headPrecheck());
	}

	@Bean
	@Lazy
	@ConditionalOnExpression("${analytics.account-analyze-on-startup:false} or ${analytics.admin-enabled:false}")
	public AccountAnalysisJob accountAnalysisJob(
			@Qualifier("analysisDataSource") DataSource analysisDataSource,
			AccountSynthesisPort port, AnalyticsSettings settings) {
		return new AccountAnalysisJob(analysisDataSource, port, settings);
	}
}
