package com.celfit.analytics.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 통합(속성+종합) Gemini 어댑터 계약: 프롬프트 조립·스키마·응답 매핑·어휘/등급 방어. */
class GeminiContentAnalyzerTest {

	static final String RESPONSE = """
			{"detectedBrands":[{"name":"브랜드A","evidence":"캡션 언급"}],
			 "sponsoredSignalLevel":"엉뚱값","sponsoredSignalReasons":["#협찬"],
			 "adDisclosure":"표기 있음","detectedProductCategories":["클렌징폼","없는라벨"],
			 "detectedProducts":[{"name":"딥클렌징폼","brand":null}],
			 "vlmAttributes":[],"mainCategory":"cleansing","subCategories":["클렌징폼/젤","클렌징폼"],
			 "detectedDistributors":["올리브영","쿠팡"],"adType":"sponsored",
			 "aiContentSummary":"평균 대비 1.2배","contentsPattern":"클렌징 루틴형",
			 "aiCommentInsight":"표본 부족","commentAuthenticityGrade":"이상값","commentAuthenticityNote":"근거"}""";

	BeautyTaxonomy taxonomy = new BeautyTaxonomy(List.of(
			new BeautyTaxonomy.Entry("cleansing", "클렌징", "클렌징폼/젤", "클렌징폼")),
			List.of("올리브영", "다이소"));

	record Call(String model, String system, String user, GeminiApi.InlineImage image, String schema) {}

	java.util.List<Call> calls = new java.util.ArrayList<>();

	GeminiApi fakeApi(String response) {
		return (model, system, user, image, schema, maxTokens) -> {
			calls.add(new Call(model, system, user, image, schema));
			return response;
		};
	}

	ContentToAnalyze content() {
		return new ContentToAnalyze("post_a", "acct1", "캡션A", "reels", 11000L, 520L, 52L,
				Map.of("recent_contents_count", 3), Map.of());
	}

	@Test
	void 통합_1콜로_속성과_종합을_함께_돌려준다() {
		GeminiContentAnalyzer analyzer = new GeminiContentAnalyzer(fakeApi(RESPONSE),
				() -> "gemini-3.1-flash-lite", () -> taxonomy);
		ContentInsightPort.ContentInsight r = analyzer.analyze(content(), null);
		assertEquals(1, calls.size());
		assertEquals("gemini-3.1-flash-lite", calls.get(0).model());
		assertEquals("브랜드A", r.attributes().detectedBrands().get(0).name());
		assertEquals("평균 대비 1.2배", r.synthesis().aiContentSummary());
	}

	@Test
	void 프롬프트에_속성_종합_절제규칙_분류표가_모두_실린다() {
		new GeminiContentAnalyzer(fakeApi(RESPONSE), () -> "m", () -> taxonomy).analyze(content(), null);
		String system = calls.get(0).system();
		assertTrue(system.contains("detectedBrands"));
		assertTrue(system.contains("aiContentSummary"));
		assertTrue(system.contains("[절제 규칙 — 반드시 지켜라]"));
		assertTrue(system.contains("클렌징폼/젤"));
		String user = calls.get(0).user();
		assertTrue(user.contains("캡션A"));
		assertTrue(user.contains("views=11000"));
	}

	@Test
	void 스키마는_속성11_종합5_필드를_모두_요구한다() {
		new GeminiContentAnalyzer(fakeApi(RESPONSE), () -> "m", () -> taxonomy).analyze(content(), null);
		String schema = calls.get(0).schema();
		assertTrue(schema.contains("\"detectedBrands\""));
		assertTrue(schema.contains("\"commentAuthenticityNote\""));
		assertTrue(schema.contains("\"required\""));
	}

	@Test
	void 어휘_밖_값은_sanitize로_걸러진다() {
		ContentInsightPort.ContentInsight r = new GeminiContentAnalyzer(fakeApi(RESPONSE),
				() -> "m", () -> taxonomy).analyze(content(), null);
		assertNull(r.attributes().sponsoredSignalLevel()); // "엉뚱값" 제거
		assertEquals(List.of("클렌징폼"), r.attributes().detectedProductCategories()); // "없는라벨" 제거
		assertEquals(List.of("올리브영"), r.attributes().detectedDistributors()); // "쿠팡" 제거
	}

	@Test
	void 등급_밖_값은_normal로_강제된다() {
		ContentInsightPort.ContentInsight r = new GeminiContentAnalyzer(fakeApi(RESPONSE),
				() -> "m", () -> taxonomy).analyze(content(), null);
		assertEquals("normal", r.synthesis().commentAuthenticityGrade());
	}
}
