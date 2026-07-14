package com.celfit.analytics.llm;

import com.anthropic.client.AnthropicClient;
import com.celfit.analytics.config.AnalyticsSettings;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * LLM 빈 배선. classify·analyze 게이트가 모두 꺼져 있으면 Anthropic 클라이언트를 아예 만들지 않는다
 * (API 키 불필요). classify-on-startup만 켜도 Synthesis/Vision 포트 빈이 함께 생기지만
 * 소비자(AnalyzeRunner)가 없으면 무해 — AnalyzeRunner 쪽 게이트는 analyze-on-startup이 지킨다.
 */
@Configuration
@ConditionalOnExpression("${analytics.classify-on-startup:false} or ${analytics.analyze-on-startup:false}")
public class LlmConfig {

	@Bean
	public AnthropicClient anthropicClient() {
		return LlmClientFactory.fromEnv(); // ANTHROPIC_AUTH_TOKEN(구독) 우선, 없으면 ANTHROPIC_API_KEY
	}

	@Bean
	public CommentClassificationPort commentClassificationPort(AnthropicClient client, AnalyticsSettings settings) {
		return new AnthropicCommentClassifier(client, settings);
	}

	@Bean
	public SynthesisPort synthesisPort(AnthropicClient client, AnalyticsSettings settings) {
		return new AnthropicSynthesizer(client, settings);
	}

	@Bean
	public VisionPort visionPort(AnthropicClient client, AnalyticsSettings settings) {
		return new AnthropicVisionAnalyzer(client, settings);
	}
}
