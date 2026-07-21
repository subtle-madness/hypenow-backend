package com.celfit.analytics.llm;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.celfit.analytics.config.AnalyticsSettings;

/** 계정 카피 Anthropic 구현 — C1 미러 수치·캡션만 근거로 인플루언서 패널 문구 7종을 생성한다. */
public final class AnthropicAccountSynthesizer implements AccountSynthesisPort {

	/** 프롬프트는 Gemini 어댑터의 검증 통과본을 공유한다 — 복제해 두면 한쪽만 고쳐지는 사고가 난다
	 *  (07-21: adHeadline 상황별 재정의 때 이 클래스만 옛 문구로 남아 컴파일로 발각). */
	private static final String INSTRUCTIONS = GeminiAccountSynthesizer.instructions();

	private final AnthropicClient client;
	private final AnalyticsSettings settings;

	public AnthropicAccountSynthesizer(AnthropicClient client, AnalyticsSettings settings) {
		this.client = client;
		this.settings = settings;
	}

	@Override
	public AccountCopy synthesize(AccountToAnalyze account) {
		String input = GeminiAccountSynthesizer.userText(account);
		StructuredMessageCreateParams<AccountCopy> params = MessageCreateParams.builder()
				.model(settings.llmModel())
				.maxTokens(4096L)
				.system(INSTRUCTIONS)
				.outputConfig(AccountCopy.class)
				.addUserMessage(input)
				.build();
		return client.messages().create(params).content().stream()
				.flatMap(block -> block.text().stream())
				.findFirst()
				.orElseThrow(() -> new IllegalStateException("계정 카피 응답에 본문 없음"))
				.text();
	}
}
