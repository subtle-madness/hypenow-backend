package com.celfit.analytics.config;

import com.celfit.analytics.admin.JobName;
import com.celfit.analytics.admin.JobProgressRegistry;
import com.celfit.analytics.analyze.AccountAnalysisJob;
import com.celfit.analytics.analyze.ContentAnalysisJob;
import com.celfit.analytics.analyze.ContentSynthesisRefreshJob;
import com.celfit.analytics.analyze.GeminiBackfillRunner;
import com.celfit.analytics.analyze.ProgressReporter;
import com.celfit.analytics.archive.GcsImageStore;
import com.celfit.analytics.archive.ImageArchiveJob;
import com.celfit.analytics.archive.ImageDownloader;
import com.celfit.analytics.archive.ImageResizer;
import com.celfit.analytics.archive.ImageStore;
import com.celfit.analytics.archive.ParImageStore;
import com.celfit.analytics.classify.CommentClassificationJob;
import com.celfit.analytics.llm.AccountSynthesisPort;
import com.celfit.analytics.llm.ContentSynthesisPort;
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
			ObjectProvider<JobProgressRegistry> progressRegistry,
			ObjectProvider<com.celfit.analytics.llm.GeminiApi> gemini,
			com.celfit.analytics.llm.BeautyTaxonomyLoader taxonomyLoader,
			ObjectProvider<com.celfit.analytics.llm.ContentFactsPort> factsPort,
			ObjectProvider<ContentSynthesisPort> synthesisPort) {
		JobProgressRegistry registry = progressRegistry.getIfAvailable();
		ProgressReporter reporter = registry != null ? registry.reporter(JobName.ANALYZE) : ProgressReporter.NOOP;
		ProgressReporter backfillReporter = registry != null
				? registry.reporter(JobName.LATE_BACKFILL_ANALYZE) : ProgressReporter.NOOP;
		ProgressReporter factsReporter = registry != null
				? registry.reporter(JobName.FACT_ANALYZE) : ProgressReporter.NOOP;
		return new ContentAnalysisJob(rawJdbcTemplate, analysisDataSource, insight,
				settings, thumbnailEnabled, headPrecheck(), reporter, backfillReporter,
				batchApiOrNull(settings, gemini), taxonomyLoader,
				factsReporter, splitPortOrNull(settings, factsPort), splitPortOrNull(settings, synthesisPort));
	}

	/**
	 * 2단계 분리(split) 전용 포트 - provider=anthropic이면 조회 자체를 하지 않는다.
	 * batchApiOrNull과 같은 관용구: @Lazy 빈이라도 getIfAvailable()은 생성을 강제해,
	 * GEMINI_API_KEY 없이 anthropic만으로 운영 중인 환경에서 불필요한 키 부재 예외를 낸다.
	 * split은 gemini/vertex 전용이며, anthropic 경로는 unified 모드로 남는다.
	 */
	private static <T> T splitPortOrNull(AnalyticsSettings settings, ObjectProvider<T> port) {
		return "anthropic".equals(settings.llmProvider()) ? null : port.getIfAvailable();
	}

	/**
	 * 배치 전송(2026-08-11) 수거 잡 — ContentAnalysisJob의 제출 전 pending 스윕과 별개로,
	 * 어드민/스케줄(JobName.BATCH_COLLECT)이 독립적으로 트리거한다.
	 */
	@Bean
	@Lazy
	@ConditionalOnExpression("${analytics.analyze-on-startup:false} or ${analytics.admin-enabled:false}")
	public com.celfit.analytics.analyze.ContentBatchCollectJob contentBatchCollectJob(
			@Qualifier("analysisDataSource") DataSource analysisDataSource,
			AnalyticsSettings settings, ObjectProvider<com.celfit.analytics.llm.GeminiApi> gemini,
			com.celfit.analytics.llm.BeautyTaxonomyLoader taxonomyLoader) {
		return new com.celfit.analytics.analyze.ContentBatchCollectJob(analysisDataSource,
				batchApiOrNull(settings, gemini), taxonomyLoader, settings);
	}

	/**
	 * GeminiApi 빈이 배치도 구현하면(VertexHttpApi) 그대로, 아니면(무료 gemini 폴백) null —
	 * 호출부는 null을 "배치 미지원"으로 해석해 온라인 경로로 내려간다.
	 *
	 * <p>provider=anthropic(롤백 경로)이면 GeminiApi 빈을 아예 조회하지 않는다 — gemini.getIfAvailable()은
	 * @Lazy 빈이라도 호출 즉시 생성을 강제해, GEMINI_API_KEY 없이 anthropic만으로 운영 중인 환경에서
	 * 불필요하게 GeminiHttpApi.fromEnv()의 키 부재 예외를 낼 수 있다(LlmConfig의 "반대편 클라이언트는
	 * 만들지 않는다" 원칙과 동형).
	 */
	private static com.celfit.analytics.llm.GeminiBatchApi batchApiOrNull(AnalyticsSettings settings,
			ObjectProvider<com.celfit.analytics.llm.GeminiApi> gemini) {
		if ("anthropic".equals(settings.llmProvider())) {
			return null;
		}
		com.celfit.analytics.llm.GeminiApi api = gemini.getIfAvailable();
		return api instanceof com.celfit.analytics.llm.GeminiBatchApi b ? b : null;
	}

	@Bean
	@Lazy
	@ConditionalOnExpression("${analytics.account-analyze-on-startup:false} or ${analytics.admin-enabled:false}")
	public AccountAnalysisJob accountAnalysisJob(
			@Qualifier("analysisDataSource") DataSource analysisDataSource,
			AccountSynthesisPort port, AnalyticsSettings settings,
			ObjectProvider<JobProgressRegistry> progressRegistry,
			com.celfit.analytics.llm.TraitTaxonomyLoader traitLoader,
			ObjectProvider<com.celfit.analytics.llm.GeminiApi> gemini) {
		JobProgressRegistry registry = progressRegistry.getIfAvailable();
		ProgressReporter reporter = registry != null
				? registry.reporter(JobName.ACCOUNT_ANALYZE) : ProgressReporter.NOOP;
		return new AccountAnalysisJob(analysisDataSource, port, settings, reporter, traitLoader,
				batchApiOrNull(settings, gemini));
	}

	/**
	 * 계정 카피 배치 수거 잡(2026-08-17) — 제출 전 스윕과 별개로 BATCH_COLLECT가 독립 트리거한다.
	 */
	@Bean
	@Lazy
	@ConditionalOnExpression("${analytics.account-analyze-on-startup:false} or ${analytics.admin-enabled:false}")
	public com.celfit.analytics.analyze.AccountBatchCollectJob accountBatchCollectJob(
			@Qualifier("analysisDataSource") DataSource analysisDataSource,
			AnalyticsSettings settings, ObjectProvider<com.celfit.analytics.llm.GeminiApi> gemini,
			com.celfit.analytics.llm.TraitTaxonomyLoader traitLoader) {
		return new com.celfit.analytics.analyze.AccountBatchCollectJob(analysisDataSource,
				batchApiOrNull(settings, gemini), traitLoader, settings);
	}

	/** 해석 문구 갱신 — 기준선 정의·문구 의미가 바뀌었을 때 낡은 행만 부분 갱신(사실 추출 보존). */
	@Bean
	@Lazy
	@ConditionalOnExpression("${analytics.synthesis-refresh-on-startup:false} or ${analytics.admin-enabled:false}")
	public ContentSynthesisRefreshJob contentSynthesisRefreshJob(JdbcTemplate rawJdbcTemplate,
			@Qualifier("analysisDataSource") DataSource analysisDataSource,
			ContentSynthesisPort port, AnalyticsSettings settings,
			ObjectProvider<JobProgressRegistry> progressRegistry) {
		JobProgressRegistry registry = progressRegistry.getIfAvailable();
		ProgressReporter reporter = registry != null
				? registry.reporter(JobName.SYNTHESIS_REFRESH) : ProgressReporter.NOOP;
		return new ContentSynthesisRefreshJob(rawJdbcTemplate, analysisDataSource, port, settings, reporter);
	}

	@Bean
	@Lazy
	@ConditionalOnExpression("${analytics.archive-on-startup:false} or ${analytics.admin-enabled:false}")
	public ImageArchiveJob imageArchiveJob(JdbcTemplate rawJdbcTemplate,
			@Qualifier("analysisDataSource") DataSource analysisDataSource,
			AnalyticsSettings settings,
			@Value("${analytics.image-par-url:}") String imageParUrl,
			@Value("${analytics.image-store:par}") String imageStoreMode,
			@Value("${analytics.image-gcs-bucket:}") String imageGcsBucket,
			@Value("${analytics.image-gcs-key:}") String imageGcsKey,
			ObjectProvider<JobProgressRegistry> progressRegistry) {
		JobProgressRegistry registry = progressRegistry.getIfAvailable();
		ProgressReporter reporter = registry != null
				? registry.reporter(JobName.ARCHIVE) : ProgressReporter.NOOP;
		// @Lazy — PAR/버킷 미설정이면 첫 트리거 때 이 잡만 실패(로그 패널 노출), 서버 기동은 영향 없음
		// IMAGE_STORE=gcs|par — OCI 복귀 보험(스펙 §실패 대응): par로 되돌리면 즉시 OCI 재개
		ImageStore store = "gcs".equalsIgnoreCase(imageStoreMode)
				? new GcsImageStore(imageGcsBucket, imageGcsKey)
				: new ParImageStore(imageParUrl);
		return new ImageArchiveJob(rawJdbcTemplate, analysisDataSource,
				store, ImageResizer.wrap(ImageDownloader.http()), settings, reporter);
	}

	/** trait 어휘 매핑 원샷(2026-07-29 스펙 §3-3) — 어드민 수동 트리거 전용, 스케줄 없음. */
	@Bean
	@Lazy
	@ConditionalOnExpression("${analytics.admin-enabled:false}")
	public com.celfit.analytics.analyze.TraitCanonJob traitCanonJob(
			@Qualifier("analysisDataSource") DataSource analysisDataSource,
			com.celfit.analytics.llm.TraitTaxonomyLoader traitLoader,
			com.celfit.analytics.llm.TraitMappingPort mappingPort,
			ObjectProvider<JobProgressRegistry> progressRegistry) {
		JobProgressRegistry registry = progressRegistry.getIfAvailable();
		ProgressReporter dry = registry != null
				? registry.reporter(JobName.TRAIT_CANON_DRY) : ProgressReporter.NOOP;
		ProgressReporter apply = registry != null
				? registry.reporter(JobName.TRAIT_CANON_APPLY) : ProgressReporter.NOOP;
		return new com.celfit.analytics.analyze.TraitCanonJob(
				analysisDataSource, traitLoader, mappingPort, dry, apply);
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
							new com.celfit.analytics.llm.TraitTaxonomyLoader(analysisDataSource),
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
