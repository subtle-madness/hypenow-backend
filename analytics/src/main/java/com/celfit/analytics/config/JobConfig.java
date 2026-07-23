package com.celfit.analytics.config;

import com.celfit.analytics.admin.JobName;
import com.celfit.analytics.admin.JobProgressRegistry;
import com.celfit.analytics.analyze.AccountAnalysisJob;
import com.celfit.analytics.analyze.ContentAnalysisJob;
import com.celfit.analytics.analyze.GeminiBackfillRunner;
import com.celfit.analytics.analyze.ProgressReporter;
import com.celfit.analytics.archive.ImageArchiveJob;
import com.celfit.analytics.archive.ImageDownloader;
import com.celfit.analytics.archive.ParImageStore;
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
import org.springframework.beans.factory.ObjectProvider;
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
			@Value("${analytics.vlm-enabled:false}") boolean thumbnailEnabled,
			ObjectProvider<JobProgressRegistry> progressRegistry) {
		JobProgressRegistry registry = progressRegistry.getIfAvailable();
		ProgressReporter reporter = registry != null ? registry.reporter(JobName.ANALYZE) : ProgressReporter.NOOP;
		ProgressReporter backfillReporter = registry != null
				? registry.reporter(JobName.LATE_BACKFILL_ANALYZE) : ProgressReporter.NOOP;
		return new ContentAnalysisJob(rawJdbcTemplate, analysisDataSource, insight,
				settings, thumbnailEnabled, headPrecheck(), reporter, backfillReporter);
	}

	@Bean
	@Lazy
	@ConditionalOnExpression("${analytics.account-analyze-on-startup:false} or ${analytics.admin-enabled:false}")
	public AccountAnalysisJob accountAnalysisJob(
			@Qualifier("analysisDataSource") DataSource analysisDataSource,
			AccountSynthesisPort port, AnalyticsSettings settings,
			ObjectProvider<JobProgressRegistry> progressRegistry) {
		JobProgressRegistry registry = progressRegistry.getIfAvailable();
		ProgressReporter reporter = registry != null
				? registry.reporter(JobName.ACCOUNT_ANALYZE) : ProgressReporter.NOOP;
		return new AccountAnalysisJob(analysisDataSource, port, settings, reporter);
	}

	@Bean
	@Lazy
	@ConditionalOnExpression("${analytics.archive-on-startup:false} or ${analytics.admin-enabled:false}")
	public ImageArchiveJob imageArchiveJob(JdbcTemplate rawJdbcTemplate,
			@Qualifier("analysisDataSource") DataSource analysisDataSource,
			AnalyticsSettings settings,
			@Value("${analytics.image-par-url:}") String imageParUrl,
			ObjectProvider<JobProgressRegistry> progressRegistry) {
		JobProgressRegistry registry = progressRegistry.getIfAvailable();
		ProgressReporter reporter = registry != null
				? registry.reporter(JobName.ARCHIVE) : ProgressReporter.NOOP;
		// @Lazy — PAR 미설정이면 첫 트리거 때 이 잡만 실패(로그 패널 노출), 서버 기동은 영향 없음
		return new ImageArchiveJob(rawJdbcTemplate, analysisDataSource,
				new ParImageStore(imageParUrl), ImageDownloader.http(), settings, reporter);
	}

	/**
	 * 구독 버스트 one-shot(07-19) — Gemini 무료 일 한도를 넘는 일회 물량을 Claude 구독 컴퓨트로 소화.
	 * export → 드라이버(analytics/export/claude_burst_driver.py, claude -p 병렬) → collect 3단 실행.
	 */
	@Bean
	@Lazy
	@ConditionalOnExpression("'${analytics.claude-burst:}' != ''")
	public org.springframework.boot.ApplicationRunner claudeBurstRunner(JdbcTemplate rawJdbcTemplate,
			@Qualifier("analysisDataSource") DataSource analysisDataSource, AnalyticsSettings settings,
			@Value("${analytics.claude-burst:}") String mode,
			@Value("${analytics.burst-dir:./burst}") String dir) {
		return args -> {
			com.celfit.analytics.analyze.ClaudeBurstRunner runner =
					new com.celfit.analytics.analyze.ClaudeBurstRunner(rawJdbcTemplate, analysisDataSource,
							settings, new com.celfit.analytics.llm.BeautyTaxonomyLoader(analysisDataSource),
							java.nio.file.Path.of(dir));
			switch (mode) {
				case "export" -> runner.export();
				case "collect" -> runner.collect();
				default -> throw new IllegalArgumentException(
						"analytics.claude-burst는 export|collect — 입력: " + mode);
			}
		};
	}

	/**
	 * 초기 백필 one-shot(07-18 확정 — 유료 키 Batch, 미러 one-shot CLI 컨벤션과 동형).
	 * submit → (배치 완료 대기, ≤24h) → collect 2단 실행. 신 스키마 뷰(04·03) 적용 후에만 유효.
	 * provider=vertex면 Vertex 배치(GCS)를 대신 사용한다.
	 */
	@Bean
	@Lazy
	@ConditionalOnExpression("${analytics.backfill-submit:false} or '${analytics.backfill-collect:}' != ''")
	public org.springframework.boot.ApplicationRunner geminiBackfillRunner(JdbcTemplate rawJdbcTemplate,
			@Qualifier("analysisDataSource") DataSource analysisDataSource, AnalyticsSettings settings,
			@Value("${analytics.backfill-submit:false}") boolean submit,
			@Value("${analytics.backfill-collect:}") String collectBatch,
			@Value("${analytics.backfill-dir:./backfill}") String dir) {
		return args -> {
			com.celfit.analytics.llm.GeminiBatchApi batchApi =
					"vertex".equals(settings.llmProvider())
							? com.celfit.analytics.llm.VertexHttpApi.fromEnv(settings)
							: com.celfit.analytics.llm.GeminiHttpApi.fromEnvPaid();
			GeminiBackfillRunner runner = new GeminiBackfillRunner(rawJdbcTemplate, analysisDataSource,
					batchApi, settings,
					new com.celfit.analytics.llm.BeautyTaxonomyLoader(analysisDataSource),
					java.nio.file.Path.of(dir));
			if (submit) {
				runner.submit();
			} else {
				runner.collect(collectBatch);
			}
		};
	}
}
