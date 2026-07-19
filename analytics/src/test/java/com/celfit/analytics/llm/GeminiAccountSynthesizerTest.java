package com.celfit.analytics.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 계정 카피 Gemini 어댑터 계약: 입력 포맷·GUARD 포함 프롬프트·레코드 매핑. */
class GeminiAccountSynthesizerTest {

	static final String RESPONSE = """
			{"tagline":"저자극 스킨케어 리뷰 톤","summary":"요약 문장","trendNote":"상승 12%",
			 "chartNote":"상위 3개가 견인","traits":["정보형","스킨케어"],"adHeadline":"","paceNote":"주 2회"}""";

	record Call(String model, String system, String user, String schema) {}

	java.util.List<Call> calls = new java.util.ArrayList<>();

	GeminiApi fakeApi() {
		return (model, system, user, image, schema, maxTokens) -> {
			calls.add(new Call(model, system, user, schema));
			return RESPONSE;
		};
	}

	@Test
	void 카피_7종을_레코드로_돌려준다() {
		AccountCopy copy = new GeminiAccountSynthesizer(fakeApi(), () -> "gemini-3.1-flash-lite")
				.synthesize(new AccountToAnalyze("acct1", Map.of("avg_views", 1000),
						List.of(Map.of("main_group", "cleansing")), List.of(), false));
		assertEquals("저자극 스킨케어 리뷰 톤", copy.tagline());
		assertEquals(List.of("정보형", "스킨케어"), copy.traits());
		assertEquals("gemini-3.1-flash-lite", calls.get(0).model());
	}

	@Test
	void 프롬프트에_절제규칙과_광고비교_유무가_실린다() {
		new GeminiAccountSynthesizer(fakeApi(), () -> "m")
				.synthesize(new AccountToAnalyze("acct1", Map.of(), List.of(), List.of(), false));
		assertTrue(calls.get(0).system().contains("[절제 규칙 — 반드시 지켜라]"));
		assertTrue(calls.get(0).user().contains("광고 비교 데이터: 없음"));
		assertTrue(calls.get(0).schema().contains("adHeadline"));
	}
}
