package com.celfit.analytics.llm;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.ImageBlockParam;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StructuredMessage;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.UrlImageSource;
import com.celfit.analytics.config.AnalyticsSettings;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * VLM Anthropic 구현 — 썸네일 1장 + 캡션 기반 (F-2 스파이크로 검증한 최소 입력안).
 * 분류 어휘는 {@link BeautyTaxonomy}(celfit-front 배포본 계약) 분류표를 프롬프트에 그대로 싣는다.
 */
public final class AnthropicVisionAnalyzer implements VisionPort {

	private static final Logger log = LoggerFactory.getLogger(AnthropicVisionAnalyzer.class);

	private static final Set<String> SIGNAL_LEVELS = Set.of("high", "mid", "low");
	private static final Set<String> AD_TYPES = Set.of("organic", "sponsored");

	private static final String INSTRUCTIONS = """
			당신은 뷰티 콘텐츠의 이미지 분석가다. 썸네일과 캡션을 보고 다음을 추출하라.
			확신이 없는 항목은 null 또는 빈 배열로 두고 지어내지 마라. 한국어로.

			- detectedBrands: 화면·캡션에서 확인되는 브랜드 {name, evidence(근거)}
			- sponsoredSignalLevel: 광고성 high|mid|low, sponsoredSignalReasons: 근거 나열
			- adDisclosure: 광고 고지 여부 (예: "캡션 #협찬 표기 있음", 없으면 "표기 없음")
			- mainCategory: 아래 분류표의 대분류 영문 값 중 하나
			- subCategories: 이 콘텐츠에 해당하는 중분류·소분류 라벨 전부 — 분류표의 표기 그대로
			  (예: 립틴트 콘텐츠면 ["립메이크업","립틴트"])
			- detectedProductCategories: 화면·캡션에서 확인되는 제품들의 소분류 라벨 — 분류표의 표기 그대로
			- detectedDistributors: 화면·캡션에서 확인되는 유통 채널 — 올리브영|다이소 만, 그 외 상호는 제외
			- vlmAttributes: {label, value} — 노출 제품 / 제품 노출 비중 / 후킹 요소 / 전환 장치 /
			  콘텐츠 유형 / 무드 / 편집 스타일 순
			- adType: organic|sponsored (캡션 표기+화면 종합 판정)

			[분류표 — 대분류(한글): 중분류[소분류, …]]
			""" + BeautyTaxonomy.promptTable();

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
		StructuredMessage<VlmResult> message = client.messages().create(params);
		// 건당 비용 실측 근거 (F-2 스파이크·운영 모니터링)
		log.info("VLM usage: input={} output={}",
				message.usage().inputTokens(), message.usage().outputTokens());
		return sanitize(message.content().stream()
				.flatMap(block -> block.text().stream())
				.findFirst()
				.orElseThrow(() -> new IllegalStateException("VLM 응답에 본문 없음"))
				.text());
	}

	/**
	 * LLM이 어휘 밖 값을 지어낸 경우 제거한다 — 스칼라는 null로(DB CHECK 위반 → 콘텐츠 전체 실패 차단),
	 * 배열은 어휘 밖 원소만 걸러낸다(was가 verbatim 매칭하므로 어휘 밖 라벨은 필터에 안 잡히는 노이즈).
	 * Synthesis의 등급 방어(AnthropicSynthesizer)와 대칭.
	 */
	static VlmResult sanitize(VlmResult raw) {
		return new VlmResult(
				raw.detectedBrands(),
				keepIfIn(raw.sponsoredSignalLevel(), SIGNAL_LEVELS),
				raw.sponsoredSignalReasons(),
				raw.adDisclosure(),
				filterToVocabulary(raw.detectedProductCategories(), BeautyTaxonomy.allSubLabels()),
				raw.vlmAttributes(),
				keepIfIn(raw.mainCategory(), BeautyTaxonomy.MAIN_CATEGORIES),
				filterToVocabulary(raw.subCategories(), BeautyTaxonomy.allMidAndSubLabels()),
				filterToVocabulary(raw.detectedDistributors(), BeautyTaxonomy.DISTRIBUTORS),
				keepIfIn(raw.adType(), AD_TYPES));
	}

	private static String keepIfIn(String value, Set<String> vocabulary) {
		return value != null && vocabulary.contains(value) ? value : null;
	}

	private static List<String> filterToVocabulary(List<String> values, Set<String> vocabulary) {
		return values == null ? null : values.stream().filter(vocabulary::contains).toList();
	}
}
