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
}
