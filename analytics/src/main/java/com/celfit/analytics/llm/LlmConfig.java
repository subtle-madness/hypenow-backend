package com.celfit.analytics.llm;

import com.anthropic.client.AnthropicClient;
import com.celfit.analytics.config.AnalyticsSettings;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

/**
 * LLM 빈 배선. classify·analyze·account-analyze 게이트가 모두 꺼져 있으면 Anthropic 클라이언트를
 * 아예 만들지 않는다 (API 키 불필요). classify-on-startup만 켜도 Synthesis/Vision 포트 빈이 함께 생기지만
 * 소비자(AnalyzeRunner)가 없으면 무해 — AnalyzeRunner·AccountAnalyzeRunner 쪽 게이트는 각자
 * analyze-on-startup·account-analyze-on-startup이 지킨다.
 * 어드민 모드(analytics.admin-enabled)에서는 UI 트리거가 소비자 — 전 빈 @Lazy라
 * 기동 시 키가 없어도 뜨고, LLM 잡 첫 실행 때 생성된다.
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
	public SynthesisPort synthesisPort(AnthropicClient client, AnalyticsSettings settings) {
		return new AnthropicSynthesizer(client, settings);
	}

	@Bean
	@Lazy
	public BeautyTaxonomyLoader beautyTaxonomyLoader(
			@Qualifier("analysisDataSource") DataSource analysisDataSource) {
		return new BeautyTaxonomyLoader(analysisDataSource);
	}

	@Bean
	@Lazy
	public ContentAttributePort contentAttributePort(AnthropicClient client, AnalyticsSettings settings,
			BeautyTaxonomyLoader taxonomyLoader) {
		return new AnthropicContentAttributeAnalyzer(client, settings, taxonomyLoader);
	}

	@Bean
	@Lazy
	public AccountSynthesisPort accountSynthesisPort(AnthropicClient client, AnalyticsSettings settings) {
		return new AnthropicAccountSynthesizer(client, settings);
	}
}
