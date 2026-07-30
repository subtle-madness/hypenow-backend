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
			 "vlmAttributes":[],"isBeauty":true,"mainCategory":"cleansing","subCategories":["클렌징폼/젤","클렌징폼"],
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
		return content("reels", null);
	}

	ContentToAnalyze content(String contentType, Boolean adMarked) {
		return new ContentToAnalyze("post_a", "acct1", "캡션A", contentType, 11000L, 520L, 52L,
				Map.of("recent_contents_count", 3), Map.of(), adMarked);
	}

	/**
	 * 인스타 유료 파트너십 태그는 인스타가 보증하는 확정 사실이라 프롬프트에 반드시 실려야 한다 —
	 * 안 실으면 태그가 붙은 게시물을 LLM이 organic으로 뒤집는다(운영 실측 87건).
	 */
	@Test
	void 공식_광고태그가_사실로_프롬프트에_실린다() {
		new GeminiContentAnalyzer(fakeApi(RESPONSE), () -> "m", () -> taxonomy)
				.analyze(content("reels", true), null);

		assertTrue(calls.get(0).user().contains("인스타 유료 파트너십 태그: 있음"), calls.get(0).user());
	}

	@Test
	void 태그가_없는_릴스는_없음으로_실린다() {
		new GeminiContentAnalyzer(fakeApi(RESPONSE), () -> "m", () -> taxonomy)
				.analyze(content("reels", false), null);

		assertTrue(calls.get(0).user().contains("인스타 유료 파트너십 태그: 없음"), calls.get(0).user());
	}

	/**
	 * 피드는 태그 기능 자체가 없다 — "없음"으로 쓰면 "광고 아님"으로 오독된다.
	 * 미러(v_contents)가 피드에 false를 채우므로 값이 아니라 콘텐츠 타입으로 판정해야 한다.
	 */
	@Test
	void 피드는_값이_false여도_해당없음으로_실린다() {
		new GeminiContentAnalyzer(fakeApi(RESPONSE), () -> "m", () -> taxonomy)
				.analyze(content("feed", false), null);

		assertTrue(calls.get(0).user().contains("인스타 유료 파트너십 태그: 해당 없음"), calls.get(0).user());
	}

	/** 릴스인데 값이 없으면 "없음"이 아니라 "확인 안 됨" — 근거 없는 organic 판정 방지. */
	@Test
	void 릴스인데_값이_없으면_확인_안됨으로_실린다() {
		new GeminiContentAnalyzer(fakeApi(RESPONSE), () -> "m", () -> taxonomy)
				.analyze(content("reels", null), null);

		assertTrue(calls.get(0).user().contains("인스타 유료 파트너십 태그: 확인 안 됨"), calls.get(0).user());
	}

	/** 태그의 증거력은 한 방향뿐 — 있으면 확정 광고, 없으면 판단 보류(캡션 고지가 정본). */
	@Test
	void 지시문이_태그의_단방향_증거력을_명시한다() {
		new GeminiContentAnalyzer(fakeApi(RESPONSE), () -> "m", () -> taxonomy).analyze(content(), null);

		String system = calls.get(0).system();
		assertTrue(system.contains("반드시 sponsored"), system);
		assertTrue(system.contains("'없음'은 광고가 아니라는 뜻이 아니다"), system);
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
		assertTrue(system.contains("[파트 B 절제 규칙 — 반드시 지켜라]"));
		assertTrue(system.contains("뷰티와 무관한 콘텐츠면 mainCategory는 null"));
		assertTrue(system.contains("클렌징폼/젤"));
		String user = calls.get(0).user();
		assertTrue(user.contains("캡션A"));
		assertTrue(user.contains("views=11000"));
	}

	/**
	 * 회귀 방지(2026-07-30 계정/콘텐츠 스코프 분리) — 콘텐츠 해석 문구는 특정 게시물 1건의 사실을
	 * 다뤄 수치가 낡지 않으므로, LlmGuard의 "근거 수치를 함께 인용하라" 지시는 이 경로에서 그대로
	 * 유지돼야 한다(계정 카피 경로에서만 뺐다 — GeminiAccountSynthesizerTest 참조).
	 */
	@Test
	void 프롬프트에_근거_수치_인용_지시가_그대로_남아있다() {
		new GeminiContentAnalyzer(fakeApi(RESPONSE), () -> "m", () -> taxonomy).analyze(content(), null);
		String system = calls.get(0).system();
		assertTrue(system.contains("핵심 주장에는 근거 수치를 함께 인용하라"), system);
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

	@Test
	void isBeauty를_파싱해_속성에_싣는다() {
		ContentInsightPort.ContentInsight r = new GeminiContentAnalyzer(fakeApi(RESPONSE),
				() -> "m", () -> taxonomy).analyze(content(), null);
		assertEquals(Boolean.TRUE, r.attributes().isBeauty());
	}

	@Test
	void 스키마는_isBeauty를_요구하고_mainCategory_앞에서_생성한다() {
		new GeminiContentAnalyzer(fakeApi(RESPONSE), () -> "m", () -> taxonomy).analyze(content(), null);
		String schema = calls.get(0).schema();
		assertTrue(schema.contains("\"isBeauty\""));
		// propertyOrdering 배열 안에서 isBeauty가 mainCategory보다 앞서 생성되는지(눈속임 방지: 구간 스코프)
		String ordering = schema.substring(schema.indexOf("propertyOrdering"));
		assertTrue(ordering.indexOf("\"isBeauty\"") < ordering.indexOf("\"mainCategory\""));
	}
}
