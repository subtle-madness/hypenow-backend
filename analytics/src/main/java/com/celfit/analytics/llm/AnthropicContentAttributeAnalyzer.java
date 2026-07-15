package com.celfit.analytics.llm;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.Base64ImageSource;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.ImageBlockParam;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StructuredMessage;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.anthropic.models.messages.TextBlockParam;
import com.celfit.analytics.config.AnalyticsSettings;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 콘텐츠 속성 분석 Anthropic 구현 — 캡션 주, 썸네일은 살아있을 때만 보조 입력 (2026-07-14 캡션 분류 스펙).
 * 분류 어휘는 {@link BeautyTaxonomyLoader}가 주는 스냅샷을 프롬프트와 sanitize가 공유한다.
 *
 * <p>이미지는 직접 내려받아 base64로 넣는다 — URL 입력은 Anthropic이 인스타 CDN을
 * robots.txt 사유로 전면 거부(400)해 불가 (F-2 실측 2026-07-14).
 */
public final class AnthropicContentAttributeAnalyzer implements ContentAttributePort {

	private static final Logger log = LoggerFactory.getLogger(AnthropicContentAttributeAnalyzer.class);

	private static final Set<String> SIGNAL_LEVELS = Set.of("high", "mid", "low");
	private static final Set<String> AD_TYPES = Set.of("organic", "sponsored");

	private final AnthropicClient client;
	private final AnalyticsSettings settings;
	private final BeautyTaxonomyLoader taxonomyLoader;
	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

	public AnthropicContentAttributeAnalyzer(AnthropicClient client, AnalyticsSettings settings,
			BeautyTaxonomyLoader taxonomyLoader) {
		this.client = client;
		this.settings = settings;
		this.taxonomyLoader = taxonomyLoader;
	}

	static String instructions(BeautyTaxonomy taxonomy) {
		return """
				당신은 뷰티 콘텐츠 분석가다. 캡션(과 썸네일이 주어지면 썸네일)을 보고 다음을 추출하라.
				확신이 없는 항목은 null 또는 빈 배열로 두고 지어내지 마라. 한국어로.

				- detectedBrands: 캡션·화면에서 확인되는 브랜드 {name, evidence(근거)} —
				  브랜드를 특정할 수 없는 제품은 목록에서 제외하라 ("미상"/"불명확" 같은 표기 금지)
				- sponsoredSignalLevel: 광고성 high|mid|low, sponsoredSignalReasons: 근거 나열
				- adDisclosure: 광고 고지 여부 (예: "캡션 #협찬 표기 있음", 없으면 "표기 없음")
				- mainCategory: 아래 분류표의 대분류 영문 값 중 하나
				- subCategories: 이 콘텐츠에 해당하는 중분류·소분류 라벨 전부 — 분류표의 표기 그대로
				  (예: 립틴트 콘텐츠면 ["립메이크업","립틴트"])
				- detectedProductCategories: 확인되는 제품들의 소분류 라벨 — 분류표의 표기 그대로
				- detectedProducts: 확인되는 제품명 {name(상품명), brand(그 제품의 브랜드, 미상이면 null)}
				- detectedDistributors: 확인되는 유통 채널 — %s 만, 그 외 상호는 제외
				- vlmAttributes: {label, value} — 노출 제품 / 제품 노출 비중 / 후킹 요소 / 전환 장치 /
				  콘텐츠 유형 / 무드 / 편집 스타일 순 (썸네일 없이 판단 불가한 항목은 제외)
				- adType: organic|sponsored (캡션 표기+화면 종합 판정)

				[분류표 — 대분류(한글): 중분류[소분류, …]]
				%s""".formatted(taxonomy.distributorsPrompt(), taxonomy.promptTable());
	}

	@Override
	public ContentAttributes analyze(String caption, String thumbnailUrl) {
		BeautyTaxonomy taxonomy = taxonomyLoader.get();
		List<ContentBlockParam> blocks = new ArrayList<>();
		if (thumbnailUrl != null) {
			blocks.add(ContentBlockParam.ofImage(ImageBlockParam.builder()
					.source(download(thumbnailUrl))
					.build()));
		}
		blocks.add(ContentBlockParam.ofText(TextBlockParam.builder()
				.text("캡션: " + (caption == null ? "(없음)" : caption)).build()));
		StructuredMessageCreateParams<ContentAttributes> params = MessageCreateParams.builder()
				.model(settings.llmModel())
				.maxTokens(4096L)
				.system(instructions(taxonomy))
				.outputConfig(ContentAttributes.class)
				.addUserMessageOfBlockParams(blocks)
				.addUserMessage(thumbnailUrl != null ? "위 썸네일과 캡션을 분석하라." : "위 캡션을 분석하라.")
				.build();
		StructuredMessage<ContentAttributes> message = client.messages().create(params);
		// 건당 비용 실측 근거 (F-2 스파이크·운영 모니터링)
		log.info("attribute usage: input={} output={} (thumbnail={})",
				message.usage().inputTokens(), message.usage().outputTokens(), thumbnailUrl != null);
		return sanitize(message.content().stream()
				.flatMap(block -> block.text().stream())
				.findFirst()
				.orElseThrow(() -> new IllegalStateException("속성 분석 응답에 본문 없음"))
				.text(), taxonomy);
	}

	/** 썸네일을 직접 내려받아 base64 소스로. 실패는 예외 → 콘텐츠 실패(일시 장애는 다음 실행 재대상). */
	private Base64ImageSource download(String thumbnailUrl) {
		try {
			HttpRequest req = HttpRequest.newBuilder(URI.create(thumbnailUrl))
					.timeout(Duration.ofSeconds(15)).build();
			HttpResponse<byte[]> res = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
			if (res.statusCode() < 200 || res.statusCode() >= 300) {
				throw new IllegalStateException("썸네일 다운로드 실패 HTTP " + res.statusCode());
			}
			return Base64ImageSource.builder()
					.mediaType(mediaTypeOf(res.headers().firstValue("content-type").orElse(null)))
					.data(Base64.getEncoder().encodeToString(res.body()))
					.build();
		} catch (java.io.IOException | InterruptedException e) {
			throw new IllegalStateException("썸네일 다운로드 실패: " + thumbnailUrl, e);
		}
	}

	/** Content-Type → SDK MediaType. 인스타 CDN은 jpeg/webp 혼재 — 미상은 jpeg로 간주. */
	static Base64ImageSource.MediaType mediaTypeOf(String contentType) {
		if (contentType == null) {
			return Base64ImageSource.MediaType.IMAGE_JPEG;
		}
		return switch (contentType.split(";")[0].trim().toLowerCase()) {
			case "image/png" -> Base64ImageSource.MediaType.IMAGE_PNG;
			case "image/gif" -> Base64ImageSource.MediaType.IMAGE_GIF;
			case "image/webp" -> Base64ImageSource.MediaType.IMAGE_WEBP;
			default -> Base64ImageSource.MediaType.IMAGE_JPEG;
		};
	}

	/**
	 * LLM이 어휘 밖 값을 지어낸 경우 제거한다 — 스칼라는 null로, 배열은 어휘 밖 원소만 걸러낸다
	 * (was가 verbatim 매칭하므로 어휘 밖 라벨은 필터에 안 잡히는 노이즈).
	 * detectedBrands·detectedProducts는 자유 텍스트라 통과. Synthesis의 등급 방어와 대칭.
	 */
	static ContentAttributes sanitize(ContentAttributes raw, BeautyTaxonomy taxonomy) {
		return new ContentAttributes(
				raw.detectedBrands(),
				keepIfIn(raw.sponsoredSignalLevel(), SIGNAL_LEVELS),
				raw.sponsoredSignalReasons(),
				raw.adDisclosure(),
				filterToVocabulary(raw.detectedProductCategories(), taxonomy.allSubLabels()),
				raw.detectedProducts(),
				raw.vlmAttributes(),
				keepIfIn(raw.mainCategory(), taxonomy.mainCategories()),
				filterToVocabulary(raw.subCategories(), taxonomy.allMidAndSubLabels()),
				filterToVocabulary(raw.detectedDistributors(), taxonomy.distributors()),
				keepIfIn(raw.adType(), AD_TYPES));
	}

	private static String keepIfIn(String value, Set<String> vocabulary) {
		return value != null && vocabulary.contains(value) ? value : null;
	}

	private static List<String> filterToVocabulary(List<String> values, Set<String> vocabulary) {
		return values == null ? null : values.stream().filter(vocabulary::contains).toList();
	}
}
