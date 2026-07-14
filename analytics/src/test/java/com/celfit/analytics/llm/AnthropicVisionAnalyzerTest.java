package com.celfit.analytics.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.Test;

/** VLM 어휘 방어(sanitize) 단위 테스트 — 클라이언트 불필요, 정적 메서드만 검증. */
class AnthropicVisionAnalyzerTest {

	private VlmResult vlmWith(String level, String adType) {
		return new VlmResult(List.of(new VlmResult.Brand("브랜드A", "화면 노출")), level,
				List.of("협찬 표기"), "표기 있음", List.of("클렌징"),
				List.of(new VlmResult.Attribute("무드", "화사함")), "skincare", List.of("클렌징폼"), adType);
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
	}

	@Test
	void 이미지_매직바이트로_media_type을_판별한다() {
		// URL 소스는 robots.txt로 거절되므로(2026-07-14) 직접 받아 base64로 보낸다 — 그때 필요한 판별
		assertEquals("image/jpeg", AnthropicVisionAnalyzer.mediaTypeOf(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00}));
		assertEquals("image/png", AnthropicVisionAnalyzer.mediaTypeOf(new byte[] {(byte) 0x89, 'P', 'N', 'G'}));
		assertEquals("image/gif", AnthropicVisionAnalyzer.mediaTypeOf(new byte[] {'G', 'I', 'F', '8'}));
		assertEquals("image/webp", AnthropicVisionAnalyzer.mediaTypeOf(
				new byte[] {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P'}));
		// 미지의 형식은 인스타 CDN 기본값인 jpeg로 폴백
		assertEquals("image/jpeg", AnthropicVisionAnalyzer.mediaTypeOf(new byte[] {0x00, 0x01}));
	}
}
