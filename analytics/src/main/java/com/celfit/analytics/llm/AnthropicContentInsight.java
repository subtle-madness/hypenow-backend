package com.celfit.analytics.llm;

/**
 * 통합 포트의 Anthropic 경로 — 기존 어댑터 2종(속성·종합)을 그대로 2콜로 감싼다.
 * app_setting(analytics.llm-provider=anthropic) 롤백 스위치용.
 */
public final class AnthropicContentInsight implements ContentInsightPort {

	private final ContentAttributePort attributes;
	private final SynthesisPort synthesis;

	public AnthropicContentInsight(ContentAttributePort attributes, SynthesisPort synthesis) {
		this.attributes = attributes;
		this.synthesis = synthesis;
	}

	@Override
	public ContentInsight analyze(ContentToAnalyze content, String thumbnailUrl) {
		boolean hasCaption = content.caption() != null && !content.caption().isBlank();
		ContentAttributes attrs = hasCaption || thumbnailUrl != null
				? attributes.analyze(content.caption(), thumbnailUrl)
				: null;
		return new ContentInsight(attrs, synthesis.synthesize(content));
	}
}
