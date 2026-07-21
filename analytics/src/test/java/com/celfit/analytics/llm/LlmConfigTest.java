package com.celfit.analytics.llm;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** provider=vertex 그레이스풀 폴백 판정(useVertex) 회귀 고정. */
class LlmConfigTest {

	@Test
	void vertex는_프로바이더가_vertex이고_SA키가_있을_때만_사용() {
		assertTrue(LlmConfig.useVertex("vertex", true));
	}

	@Test
	void provider가_vertex라도_SA키가_없으면_gemini로_폴백() {
		assertFalse(LlmConfig.useVertex("vertex", false));
	}

	@Test
	void provider가_vertex가_아니면_SA키_유무와_무관하게_vertex_미사용() {
		assertFalse(LlmConfig.useVertex("gemini", true));
		assertFalse(LlmConfig.useVertex("anthropic", true));
	}
}
