package com.celfit.analytics.llm;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.celfit.analytics.config.AnalyticsSettings;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** LLM 빈 배선. 분류 게이트가 꺼져 있으면 Anthropic 클라이언트를 아예 만들지 않는다 (API 키 불필요). */
@Configuration
@ConditionalOnProperty(name = "analytics.classify-on-startup", havingValue = "true")
public class LlmConfig {

	@Bean
	public AnthropicClient anthropicClient() {
		return AnthropicOkHttpClient.fromEnv(); // ANTHROPIC_API_KEY 필요
	}

	@Bean
	public CommentClassificationPort commentClassificationPort(AnthropicClient client, AnalyticsSettings settings) {
		return new AnthropicCommentClassifier(client, settings);
	}
}
