package com.celfit.analytics.llm;

import com.anthropic.client.AnthropicClient;
import com.celfit.analytics.config.AnalyticsSettings;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

/**
 * LLM 빈 배선. 게이트가 모두 꺼져 있으면 클라이언트를 아예 만들지 않는다 (API 키 불필요).
 * 전 빈 @Lazy라 기동 시 키가 없어도 뜨고, LLM 잡 첫 실행 때 생성된다.
 *
 * <p>프로바이더 선택(07-18 확정): app_setting `analytics.llm-provider` — gemini(기본) | anthropic(롤백).
 * 포트 빈 생성 시점에 읽으므로 전환은 재기동 필요. ObjectProvider로 반대편 클라이언트는 만들지 않아
 * gemini 운영 시 ANTHROPIC 키가 없어도 된다(댓글 분류는 MVP 휴면이라 Anthropic 유지 — 미호출이면 무해).
 */
@Configuration
@ConditionalOnExpression("${analytics.classify-on-startup:false} or ${analytics.analyze-on-startup:false}"
		+ " or ${analytics.account-analyze-on-startup:false} or ${analytics.admin-enabled:false}")
public class LlmConfig {

	@Bean
	@Lazy
	public AnthropicClient anthropicClient() {
		return LlmClientFactory.fromEnv(); // ANTHROPIC_AUTH_TOKEN(구독) 우선, 없으면 ANTHROPIC_API_KEY
	}

	@Bean
	@Lazy
	public CommentClassificationPort commentClassificationPort(AnthropicClient client, AnalyticsSettings settings) {
		return new AnthropicCommentClassifier(client, settings);
	}

	@Bean
	@Lazy
	public BeautyTaxonomyLoader beautyTaxonomyLoader(
			@Qualifier("analysisDataSource") DataSource analysisDataSource) {
		return new BeautyTaxonomyLoader(analysisDataSource);
	}

	@Bean
	@Lazy
	public GeminiApi geminiApi(AnalyticsSettings settings) {
		return GeminiHttpApi.fromEnv(settings.geminiRpm()); // GEMINI_API_KEY (무료 프로젝트)
	}

	@Bean
	@Lazy
	public ContentInsightPort contentInsightPort(AnalyticsSettings settings,
			ObjectProvider<AnthropicClient> anthropic, ObjectProvider<GeminiApi> gemini,
			BeautyTaxonomyLoader taxonomyLoader) {
		if ("anthropic".equals(settings.llmProvider())) {
			AnthropicClient client = anthropic.getObject();
			return new AnthropicContentInsight(
					new AnthropicContentAttributeAnalyzer(client, settings, taxonomyLoader),
					new AnthropicSynthesizer(client, settings));
		}
		return new GeminiContentAnalyzer(gemini.getObject(), settings::geminiModel, taxonomyLoader::get);
	}

	@Bean
	@Lazy
	public AccountSynthesisPort accountSynthesisPort(AnalyticsSettings settings,
			ObjectProvider<AnthropicClient> anthropic, ObjectProvider<GeminiApi> gemini) {
		if ("anthropic".equals(settings.llmProvider())) {
			return new AnthropicAccountSynthesizer(anthropic.getObject(), settings);
		}
		return new GeminiAccountSynthesizer(gemini.getObject(), settings::geminiModel);
	}
}
