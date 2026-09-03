package com.celfit.analytics.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.celfit.analytics.analyze.AccountAnalysisJob;
import com.celfit.analytics.analyze.ContentAnalysisJob;
import com.celfit.analytics.analyze.ContentSynthesisRefreshJob;
import com.celfit.analytics.llm.LlmConfig;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

/**
 * {@link LlmConfig}+{@link JobConfig} 실배선 컨텍스트 테스트 — {@code GeminiContentAnalyzer}가
 * {@code ContentInsightPort}·{@code ContentFactsPort} 둘 다 구현해서 생기는
 * NoUniqueBeanDefinitionException 재현 (2026-09-03 스테이징 장애: 콘텐츠 분석 잡 트리거마다
 * {@code contentAnalysisJob} 빈 생성 실패, analyze-mode 무관하게 통합 ANALYZE/LATE_BACKFILL까지 깨짐).
 *
 * <p>기존 {@link JobConfigTest}는 {@code containsBeanDefinition}만 확인해 @Lazy 빈을 실제로
 * 생성하지 않아 이 버그를 못 잡았다 — 여기서는 {@code getBean}으로 강제 인스턴스화한다.
 * GEMINI_API_KEY는 build.gradle test 태스크가 더미 값을 주입한다(GeminiHttpApi 생성자는
 * 네트워크를 타지 않는다). DB는 실제로 쓰지 않으므로 JdbcTemplate·DataSource는 테스트 대역이다.
 */
class JobConfigLlmWiringTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
			.withUserConfiguration(LlmConfig.class, JobConfig.class, TestSupport.class)
			.withPropertyValues("analytics.admin-enabled=true");

	@Test
	void contentAnalysisJob_빈_생성이_ContentFactsPort_중복_후보로_실패하지_않는다() {
		runner.run(ctx -> {
			assertThat(ctx).hasNotFailed();
			assertThat(ctx.getBean(ContentAnalysisJob.class)).isNotNull();
		});
	}

	@Test
	void contentSynthesisRefreshJob_빈_생성도_정상이다() {
		runner.run(ctx -> {
			assertThat(ctx).hasNotFailed();
			assertThat(ctx.getBean(ContentSynthesisRefreshJob.class)).isNotNull();
		});
	}

	@Test
	void accountAnalysisJob_빈_생성도_정상이다() {
		runner.run(ctx -> {
			assertThat(ctx).hasNotFailed();
			assertThat(ctx.getBean(AccountAnalysisJob.class)).isNotNull();
		});
	}

	/** app_setting 조회를 전부 "미설정"으로 응답 — AnalyticsSettings가 기본값(gemini 등)으로 폴백한다. */
	@Configuration(proxyBeanMethods = false)
	static class TestSupport {

		@Bean
		JdbcTemplate rawJdbcTemplate() {
			JdbcTemplate raw = mock(JdbcTemplate.class);
			when(raw.query(org.mockito.ArgumentMatchers.anyString(),
					org.mockito.ArgumentMatchers.<ResultSetExtractor<Optional<String>>>any(),
					org.mockito.ArgumentMatchers.any()))
					.thenReturn(Optional.empty());
			return raw;
		}

		@Bean
		AnalyticsSettings analyticsSettings(JdbcTemplate rawJdbcTemplate) {
			return new AnalyticsSettings(rawJdbcTemplate);
		}

		@Bean
		@Qualifier("analysisDataSource")
		DataSource analysisDataSource() {
			// BeautyTaxonomyLoader/TraitTaxonomyLoader는 생성자에서 JdbcTemplate로 감싸기만 하고
			// 실제 쿼리는 어휘 첫 조회(get()) 시점에 나간다 — 빈 생성 단계에서는 호출되지 않는다.
			return mock(DataSource.class);
		}
	}
}
