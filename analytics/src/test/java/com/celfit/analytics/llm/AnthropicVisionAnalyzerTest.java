package com.celfit.analytics.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.Test;

/** VLM 어휘 방어(sanitize) 단위 테스트 — 클라이언트 불필요, 정적 메서드만 검증. */
class AnthropicVisionAnalyzerTest {

	private VlmResult vlmWith(String level, String adType) {
		return new VlmResult(List.of(new VlmResult.Brand("브랜드A", "화면 노출")), level,
				List.of("협찬 표기"), "표기 있음", List.of("클렌징폼"),
				List.of(new VlmResult.Attribute("무드", "화사함")), "skincare", List.of("클렌징폼/젤", "클렌징폼"),
				List.of("올리브영"), adType);
	}

	@Test
	void 어휘_밖_값은_null로_교체된다() {
		// LLM이 CHECK 제약 밖 어휘를 지어낸 경우 (예: medium, ad)
		VlmResult sanitized = AnthropicVisionAnalyzer.sanitize(vlmWith("medium", "ad"));

		assertNull(sanitized.sponsoredSignalLevel());
		assertNull(sanitized.adType());
		// 나머지 필드는 유지된다
		assertEquals("브랜드A", sanitized.detectedBrands().get(0).name());
		assertEquals("skincare", sanitized.mainCategory());
		assertEquals("표기 있음", sanitized.adDisclosure());
	}

	@Test
	void 유효_어휘는_그대로_유지된다() {
		VlmResult sanitized = AnthropicVisionAnalyzer.sanitize(vlmWith("high", "sponsored"));

		assertEquals("high", sanitized.sponsoredSignalLevel());
		assertEquals("sponsored", sanitized.adType());
		assertEquals("skincare", sanitized.mainCategory());
		assertEquals(List.of("클렌징폼/젤", "클렌징폼"), sanitized.subCategories());
		assertEquals(List.of("클렌징폼"), sanitized.detectedProductCategories());
		assertEquals(List.of("올리브영"), sanitized.detectedDistributors());
	}

	@Test
	void 분류표_밖_대분류는_null로_교체된다() {
		// 프론트 slug 6종 밖 값 (구 프롬프트 어휘 hair, 지어낸 값 등) → main_category CHECK 위반 차단
		VlmResult sanitized = AnthropicVisionAnalyzer.sanitize(new VlmResult(List.of(), "low",
				List.of(), "표기 없음", List.of(), List.of(), "hair", List.of("샴푸/스케일러"), List.of(), "organic"));

		assertNull(sanitized.mainCategory());
		assertEquals(List.of("샴푸/스케일러"), sanitized.subCategories()); // 라벨 자체는 어휘 안 — 유지
	}

	@Test
	void 분류표_밖_라벨은_배열에서_제거된다() {
		// sub_categories는 중분류+소분류 어휘, product는 소분류 어휘, 유통사는 올리브영/다이소 고정
		VlmResult sanitized = AnthropicVisionAnalyzer.sanitize(new VlmResult(List.of(), "low",
				List.of(), "표기 없음", List.of("립틴트", "틴트제품", "립메이크업"), List.of(), "makeup",
				List.of("립메이크업", "립틴트", "입술화장"), List.of("올리브영", "쿠팡"), "organic"));

		assertEquals(List.of("립메이크업", "립틴트"), sanitized.subCategories());
		// product 어휘는 소분류만 — 중분류(립메이크업)·비어휘(틴트제품) 제거
		assertEquals(List.of("립틴트"), sanitized.detectedProductCategories());
		assertEquals(List.of("올리브영"), sanitized.detectedDistributors());
	}

	@Test
	void 컨텐트타입은_SDK_미디어타입으로_매핑되고_미상은_jpeg다() {
		// 인스타 CDN 실측: image/jpeg와 image/webp 혼재, charset 파라미터 붙는 경우 방어
		assertEquals(com.anthropic.models.messages.Base64ImageSource.MediaType.IMAGE_WEBP,
				AnthropicVisionAnalyzer.mediaTypeOf("image/webp"));
		assertEquals(com.anthropic.models.messages.Base64ImageSource.MediaType.IMAGE_JPEG,
				AnthropicVisionAnalyzer.mediaTypeOf("image/jpeg; charset=binary"));
		assertEquals(com.anthropic.models.messages.Base64ImageSource.MediaType.IMAGE_JPEG,
				AnthropicVisionAnalyzer.mediaTypeOf(null));
		assertEquals(com.anthropic.models.messages.Base64ImageSource.MediaType.IMAGE_PNG,
				AnthropicVisionAnalyzer.mediaTypeOf("IMAGE/PNG"));
	}

	@Test
	void null_배열은_null로_유지된다() {
		VlmResult sanitized = AnthropicVisionAnalyzer.sanitize(new VlmResult(null, null,
				null, null, null, null, null, null, null, null));

		assertNull(sanitized.subCategories());
		assertNull(sanitized.detectedProductCategories());
		assertNull(sanitized.detectedDistributors());
		assertNull(sanitized.mainCategory());
	}
}
