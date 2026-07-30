package com.celfit.analytics.llm;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 계정/콘텐츠 스코프 분리 계약(2026-07-30) — RULES(콘텐츠 경로)는 근거 수치 인용 지시를 유지하고,
 * ACCOUNT_RULES(계정 카피 경로)는 뺀다. 공통 3줄(표본 헤지·추론 금지·조언 금지)은 양쪽 다 갖는다.
 */
class LlmGuardTest {

	private static final String COMMON_LINE = "조언·제안·전략 제시는 금지다";
	private static final String CITATION_LINE = "핵심 주장에는 근거 수치를 함께 인용하라";

	@Test
	void RULES는_근거_수치_인용_지시를_포함한다() {
		assertTrue(LlmGuard.RULES.contains(CITATION_LINE), LlmGuard.RULES);
		assertTrue(LlmGuard.RULES.contains(COMMON_LINE), LlmGuard.RULES);
	}

	@Test
	void ACCOUNT_RULES는_근거_수치_인용_지시를_뺀다() {
		assertTrue(!LlmGuard.ACCOUNT_RULES.contains(CITATION_LINE), LlmGuard.ACCOUNT_RULES);
		assertTrue(LlmGuard.ACCOUNT_RULES.contains(COMMON_LINE), LlmGuard.ACCOUNT_RULES);
	}

	@Test
	void BODY와_ACCOUNT_BODY도_같은_차이를_갖는다() {
		assertTrue(LlmGuard.BODY.contains(CITATION_LINE), LlmGuard.BODY);
		assertTrue(!LlmGuard.ACCOUNT_BODY.contains(CITATION_LINE), LlmGuard.ACCOUNT_BODY);
	}
}
