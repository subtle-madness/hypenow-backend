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
			new BeautyTaxonomy.Entry("skincare", "스킨케어", "스킨/토너", "스킨", "beauty"),
			new BeautyTaxonomy.Entry("makeup", "메이크업", "립메이크업", "립틴트", "beauty"),
			new BeautyTaxonomy.Entry("cleansing", "클렌징", "클렌징폼/젤", "클렌징폼", "beauty"),
			new BeautyTaxonomy.Entry("haircare", "헤어케어", "샴푸/스케일러", "샴푸", "beauty")),
			List.of(new BeautyTaxonomy.Distributor("올리브영", "beauty"),
					new BeautyTaxonomy.Distributor("다이소", "beauty")));

	private ContentAttributes attrsWith(String level, String adType) {
		return new ContentAttributes(List.of(new ContentAttributes.Brand("브랜드A", "화면 노출")), level,
				List.of("협찬 표기"), "표기 있음", List.of("클렌징폼"),
				List.of(new ContentAttributes.Product("딥클렌징폼", "브랜드A")),
				List.of(new ContentAttributes.Attribute("무드", "화사함")), "skincare",
				List.of("클렌징폼/젤", "클렌징폼"), List.of("올리브영"), adType, true);
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
	void 분류표_밖_대분류는_서브카테고리로_역유도된다() {
		// slug 어휘 밖 값(hair)이라도 sub_categories의 유효 라벨(샴푸/스케일러→haircare)로 복구
		ContentAttributes sanitized = AnthropicContentAttributeAnalyzer.sanitize(
				new ContentAttributes(List.of(), "low", List.of(), "표기 없음", List.of(), List.of(),
						List.of(), "hair", List.of("샴푸/스케일러"), List.of(), "organic", true), TAXONOMY);

		assertEquals("haircare", sanitized.mainCategory()); // 드랍 대신 역유도 복구
		assertEquals(List.of("샴푸/스케일러"), sanitized.subCategories());
	}

	@Test
	void 어휘_밖_라벨뿐이면_역유도도_실패해_null이다() {
		// 유효 sub·productCategory가 하나도 없으면 복구 불가 → null 유지
		ContentAttributes sanitized = AnthropicContentAttributeAnalyzer.sanitize(
				new ContentAttributes(List.of(), "low", List.of(), "표기 없음", List.of("없는라벨"), List.of(),
						List.of(), "hair", List.of("없는중분류"), List.of(), "organic", true), TAXONOMY);

		assertNull(sanitized.mainCategory());
	}

	@Test
	void 제품카테고리로도_역유도된다() {
		// sub_categories 비어도 detectedProductCategories의 유효 소분류로 복구
		ContentAttributes sanitized = AnthropicContentAttributeAnalyzer.sanitize(
				new ContentAttributes(List.of(), "low", List.of(), "표기 없음", List.of("립틴트"), List.of(),
						List.of(), null, List.of(), List.of(), "organic", true), TAXONOMY);

		assertEquals("makeup", sanitized.mainCategory());
	}

	@Test
	void 비뷰티_콘텐츠는_유효_대분류를_반환해도_main이_null이_된다() {
		// isBeauty=false인데 LLM이 유효 대분류(skincare)를 직접 줌 → 생산자가 null로 확정(비뷰티 불변식)
		ContentAttributes sanitized = AnthropicContentAttributeAnalyzer.sanitize(
				new ContentAttributes(List.of(), "low", List.of(), "표기 없음", List.of(), List.of(),
						List.of(), "skincare", List.of("스킨"), List.of(), "organic", false), TAXONOMY);

		assertNull(sanitized.mainCategory());
	}

	@Test
	void 비뷰티_콘텐츠는_서브라벨로_역유도되지_않는다() {
		// isBeauty=false + 유효 서브라벨(샴푸/스케일러) → 역유도해도 비뷰티라 main은 null 유지
		ContentAttributes sanitized = AnthropicContentAttributeAnalyzer.sanitize(
				new ContentAttributes(List.of(), "low", List.of(), "표기 없음", List.of(), List.of(),
						List.of(), null, List.of("샴푸/스케일러"), List.of(), "organic", false), TAXONOMY);

		assertNull(sanitized.mainCategory());
	}

	@Test
	void 분류표_밖_라벨은_배열에서_제거된다() {
		// sub_categories는 중분류+소분류 어휘, product 카테고리는 소분류 어휘, 유통사는 올리브영/다이소 고정
		ContentAttributes sanitized = AnthropicContentAttributeAnalyzer.sanitize(
				new ContentAttributes(List.of(), "low", List.of(), "표기 없음",
						List.of("립틴트", "틴트제품", "립메이크업"), List.of(), List.of(), "makeup",
						List.of("립메이크업", "립틴트", "입술화장"), List.of("올리브영", "쿠팡"), "organic", true),
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
				new ContentAttributes(null, null, null, null, null, null, null, null, null, null, null, null),
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

		assertTrue(instructions.contains("올리브영(beauty)|다이소(beauty)"));
		assertTrue(instructions.contains("[beauty] skincare(스킨케어)"));
		assertTrue(instructions.contains("detectedProducts"));
	}
}
