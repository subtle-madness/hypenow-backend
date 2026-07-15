package com.celfit.analytics.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** 속성 분석 어휘 방어(sanitize)·프롬프트 조립 단위 테스트 — 클라이언트 불필요, 정적 메서드만 검증. */
class AnthropicContentAttributeAnalyzerTest {

	// sanitize가 어휘 스냅샷을 쓰는지 검증하기 위한 소형 픽스처 (실 시드 검증은 BeautyTaxonomySeedTest)
	private static final BeautyTaxonomy TAXONOMY = new BeautyTaxonomy(List.of(
			new BeautyTaxonomy.Entry("skincare", "스킨케어", "스킨/토너", "스킨"),
			new BeautyTaxonomy.Entry("makeup", "메이크업", "립메이크업", "립틴트"),
			new BeautyTaxonomy.Entry("cleansing", "클렌징", "클렌징폼/젤", "클렌징폼"),
			new BeautyTaxonomy.Entry("haircare", "헤어케어", "샴푸/스케일러", "샴푸")),
			List.of("올리브영", "다이소"));

	private ContentAttributes attrsWith(String level, String adType) {
		return new ContentAttributes(List.of(new ContentAttributes.Brand("브랜드A", "화면 노출")), level,
				List.of("협찬 표기"), "표기 있음", List.of("클렌징폼"),
				List.of(new ContentAttributes.Product("딥클렌징폼", "브랜드A")),
				List.of(new ContentAttributes.Attribute("무드", "화사함")), "skincare",
				List.of("클렌징폼/젤", "클렌징폼"), List.of("올리브영"), adType);
	}

	@Test
	void 어휘_밖_값은_null로_교체된다() {
		// LLM이 어휘 밖 값을 지어낸 경우 (예: medium, ad)
		ContentAttributes sanitized = AnthropicContentAttributeAnalyzer.sanitize(
				attrsWith("medium", "ad"), TAXONOMY);

		assertNull(sanitized.sponsoredSignalLevel());
		assertNull(sanitized.adType());
		// 나머지 필드는 유지된다
		assertEquals("브랜드A", sanitized.detectedBrands().get(0).name());
		assertEquals("skincare", sanitized.mainCategory());
		assertEquals("표기 있음", sanitized.adDisclosure());
	}

	@Test
	void 유효_어휘는_그대로_유지되고_제품명은_통과한다() {
		ContentAttributes sanitized = AnthropicContentAttributeAnalyzer.sanitize(
				attrsWith("high", "sponsored"), TAXONOMY);

		assertEquals("high", sanitized.sponsoredSignalLevel());
		assertEquals("sponsored", sanitized.adType());
		assertEquals("skincare", sanitized.mainCategory());
		assertEquals(List.of("클렌징폼/젤", "클렌징폼"), sanitized.subCategories());
		assertEquals(List.of("클렌징폼"), sanitized.detectedProductCategories());
		assertEquals(List.of("올리브영"), sanitized.detectedDistributors());
		// 제품명은 자유 텍스트 — 어휘 필터 대상이 아니다
		assertEquals("딥클렌징폼", sanitized.detectedProducts().get(0).name());
		assertEquals("브랜드A", sanitized.detectedProducts().get(0).brand());
	}

	@Test
	void 분류표_밖_대분류는_null로_교체된다() {
		// slug 어휘 밖 값 (구 프롬프트 어휘 hair, 지어낸 값 등)
		ContentAttributes sanitized = AnthropicContentAttributeAnalyzer.sanitize(
				new ContentAttributes(List.of(), "low", List.of(), "표기 없음", List.of(), List.of(),
						List.of(), "hair", List.of("샴푸/스케일러"), List.of(), "organic"), TAXONOMY);

		assertNull(sanitized.mainCategory());
		assertEquals(List.of("샴푸/스케일러"), sanitized.subCategories()); // 라벨 자체는 어휘 안 — 유지
	}

	@Test
	void 분류표_밖_라벨은_배열에서_제거된다() {
		// sub_categories는 중분류+소분류 어휘, product 카테고리는 소분류 어휘, 유통사는 올리브영/다이소 고정
		ContentAttributes sanitized = AnthropicContentAttributeAnalyzer.sanitize(
				new ContentAttributes(List.of(), "low", List.of(), "표기 없음",
						List.of("립틴트", "틴트제품", "립메이크업"), List.of(), List.of(), "makeup",
						List.of("립메이크업", "립틴트", "입술화장"), List.of("올리브영", "쿠팡"), "organic"),
				TAXONOMY);

		assertEquals(List.of("립메이크업", "립틴트"), sanitized.subCategories());
		// product 카테고리 어휘는 소분류만 — 중분류(립메이크업)·비어휘(틴트제품) 제거
		assertEquals(List.of("립틴트"), sanitized.detectedProductCategories());
		assertEquals(List.of("올리브영"), sanitized.detectedDistributors());
	}

	@Test
	void 컨텐트타입은_SDK_미디어타입으로_매핑되고_미상은_jpeg다() {
		// 인스타 CDN 실측: image/jpeg와 image/webp 혼재, charset 파라미터 붙는 경우 방어
		assertEquals(com.anthropic.models.messages.Base64ImageSource.MediaType.IMAGE_WEBP,
				AnthropicContentAttributeAnalyzer.mediaTypeOf("image/webp"));
		assertEquals(com.anthropic.models.messages.Base64ImageSource.MediaType.IMAGE_JPEG,
				AnthropicContentAttributeAnalyzer.mediaTypeOf("image/jpeg; charset=binary"));
		assertEquals(com.anthropic.models.messages.Base64ImageSource.MediaType.IMAGE_JPEG,
				AnthropicContentAttributeAnalyzer.mediaTypeOf(null));
		assertEquals(com.anthropic.models.messages.Base64ImageSource.MediaType.IMAGE_PNG,
				AnthropicContentAttributeAnalyzer.mediaTypeOf("IMAGE/PNG"));
	}

	@Test
	void null_배열은_null로_유지된다() {
		ContentAttributes sanitized = AnthropicContentAttributeAnalyzer.sanitize(
				new ContentAttributes(null, null, null, null, null, null, null, null, null, null, null),
				TAXONOMY);

		assertNull(sanitized.subCategories());
		assertNull(sanitized.detectedProductCategories());
		assertNull(sanitized.detectedDistributors());
		assertNull(sanitized.detectedProducts());
		assertNull(sanitized.mainCategory());
	}

	@Test
	void 프롬프트는_어휘_스냅샷의_분류표와_유통사를_담는다() {
		String instructions = AnthropicContentAttributeAnalyzer.instructions(TAXONOMY);

		assertTrue(instructions.contains("올리브영|다이소"));
		assertTrue(instructions.contains("skincare(스킨케어)"));
		assertTrue(instructions.contains("detectedProducts"));
	}
}
