package com.celfit.analytics.llm;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.ImageBlockParam;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.UrlImageSource;
import com.celfit.analytics.config.AnalyticsSettings;
import java.util.List;

/**
 * VLM Anthropic 구현 — 썸네일 1장 + 캡션 기반 (F-2 스파이크의 최소 입력안).
 * 영상 프레임 입력은 스파이크 결과에 따라 확장.
 */
public final class AnthropicVisionAnalyzer implements VisionPort {

	private static final String INSTRUCTIONS = """
			당신은 뷰티 콘텐츠의 이미지 분석가다. 썸네일과 캡션을 보고 다음을 추출하라.
			확신이 없는 항목은 null 또는 빈 배열로 두고 지어내지 마라. 한국어로.

			- detectedBrands: 화면·캡션에서 확인되는 브랜드 {name, evidence(근거)}
			- sponsoredSignalLevel: 광고성 high|mid|low, sponsoredSignalReasons: 근거 나열
			- adDisclosure: 광고 고지 여부 (예: "캡션 #협찬 표기 있음", 없으면 "표기 없음")
			- detectedProductCategories: 제품 카테고리 (예: 클렌징, 립)
			- vlmAttributes: {label, value} — 노출 제품 / 제품 노출 비중 / 후킹 요소 / 전환 장치 /
			  콘텐츠 유형 / 무드 / 편집 스타일 순
			- mainCategory: makeup|skincare|hair|etc 중 하나, subCategories: 소분류 라벨
			- adType: organic|sponsored (캡션 표기+화면 종합 판정)
			""";

	private final AnthropicClient client;
	private final AnalyticsSettings settings;

	public AnthropicVisionAnalyzer(AnthropicClient client, AnalyticsSettings settings) {
		this.client = client;
		this.settings = settings;
	}

	@Override
	public VlmResult analyze(String thumbnailUrl, String caption) {
		StructuredMessageCreateParams<VlmResult> params = MessageCreateParams.builder()
				.model(settings.llmModel())
				.maxTokens(4096L)
				.system(INSTRUCTIONS)
				.outputConfig(VlmResult.class)
				.addUserMessageOfBlockParams(List.of(
						ContentBlockParam.ofImage(ImageBlockParam.builder()
								.source(UrlImageSource.builder().url(thumbnailUrl).build())
								.build()),
						ContentBlockParam.ofText(TextBlockParam.builder()
								.text("캡션: " + caption).build())))
				.addUserMessage("위 썸네일과 캡션을 분석하라.")
				.build();
		return client.messages().create(params).content().stream()
				.flatMap(block -> block.text().stream())
				.findFirst()
				.orElseThrow(() -> new IllegalStateException("VLM 응답에 본문 없음"))
				.text();
	}
}
