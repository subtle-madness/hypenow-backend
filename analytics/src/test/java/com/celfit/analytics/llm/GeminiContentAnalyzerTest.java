package com.celfit.analytics.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
			 "vlmAttributes":[],"isRelevant":true,"mainCategory":"cleansing","subCategories":["클렌징폼/젤","클렌징폼"],
			 "detectedDistributors":["올리브영","쿠팡"],"adType":"sponsored",
			 "aiContentSummary":"평균 대비 1.2배","contentsPattern":"클렌징 루틴형",
			 "aiCommentInsight":"표본 부족","commentAuthenticityGrade":"이상값","commentAuthenticityNote":"근거"}""";

	BeautyTaxonomy taxonomy = new BeautyTaxonomy(List.of(
			new BeautyTaxonomy.Entry("cleansing", "클렌징", "클렌징폼/젤", "클렌징폼", "beauty"),
			new BeautyTaxonomy.Entry("beverage", "음료", "음료", "탄산", "fnb")),
			List.of(new BeautyTaxonomy.Distributor("올리브영", "beauty"),
					new BeautyTaxonomy.Distributor("다이소", "beauty"),
					new BeautyTaxonomy.Distributor("GS25", "fnb")));

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
		assertTrue(system.contains("분류표의 어느 대분류에도 해당하지 않으면 mainCategory는 null"));
		// 축 겹침·공구 규칙이 프롬프트에 실려야 F&B 분류가 흔들리지 않는다
		assertTrue(system.contains("fnb 축으로 분류하라"));
		assertTrue(system.contains("공동구매(공구)"));
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
	void isRelevant를_파싱해_축으로_is_beauty를_판정한다() {
		// 응답은 "분류 대상인가"만 답하고, 뷰티 여부는 확정된 대분류(cleansing)의 축에서 파생된다.
		ContentInsightPort.ContentInsight r = new GeminiContentAnalyzer(fakeApi(RESPONSE),
				() -> "m", () -> taxonomy).analyze(content(), null);
		assertEquals(Boolean.TRUE, r.attributes().isRelevant());
		assertEquals(Boolean.TRUE, r.attributes().isBeauty());
	}

	@Test
	void FnB_대분류면_is_beauty가_false로_파생된다() {
		String fnb = RESPONSE
				.replace("\"mainCategory\":\"cleansing\"", "\"mainCategory\":\"beverage\"")
				.replace("[\"클렌징폼/젤\",\"클렌징폼\"]", "[\"음료\",\"탄산\"]")
				.replace("[\"클렌징폼\",\"없는라벨\"]", "[\"탄산\"]")
				.replace("[\"올리브영\",\"쿠팡\"]", "[\"GS25\"]");
		ContentInsightPort.ContentInsight r = new GeminiContentAnalyzer(fakeApi(fnb),
				() -> "m", () -> taxonomy).analyze(content(), null);
		assertEquals("beverage", r.attributes().mainCategory());
		assertEquals(Boolean.FALSE, r.attributes().isBeauty());
		assertEquals(List.of("GS25"), r.attributes().detectedDistributors());
	}

	@Test
	void 스키마는_isRelevant를_요구하고_mainCategory_앞에서_생성한다() {
		new GeminiContentAnalyzer(fakeApi(RESPONSE), () -> "m", () -> taxonomy).analyze(content(), null);
		String schema = calls.get(0).schema();
		assertTrue(schema.contains("\"isRelevant\""));
		// propertyOrdering 배열 안에서 isBeauty가 mainCategory보다 앞서 생성되는지(눈속임 방지: 구간 스코프)
		String ordering = schema.substring(schema.indexOf("propertyOrdering"));
		assertTrue(ordering.indexOf("\"isRelevant\"") < ordering.indexOf("\"mainCategory\""));
	}

	static final String FACTS_RESPONSE = """
			{"detectedBrands":[{"name":"브랜드A","evidence":"캡션 언급"}],
			 "sponsoredSignalLevel":"high","sponsoredSignalReasons":["#협찬"],
			 "adDisclosure":"표기 있음","detectedProductCategories":["클렌징폼"],
			 "detectedProducts":[{"name":"딥클렌징폼","brand":null}],
			 "vlmAttributes":[],"isRelevant":true,"mainCategory":"cleansing",
			 "subCategories":["클렌징폼/젤","클렌징폼"],
			 "detectedDistributors":["올리브영"],"adType":"sponsored"}""";

	/** 파트 A 스키마에는 해석 5필드가 없어야 한다 - 있으면 배치가 해석까지 만들어 D+1에 미성숙 수치를 인용한다. */
	@Test
	void 사실_스키마에는_파트B_5필드가_없다() {
		String schema = GeminiContentAnalyzer.RESPONSE_SCHEMA_FACTS;

		assertTrue(schema.contains("\"detectedBrands\""));
		assertTrue(schema.contains("\"isRelevant\""));
		assertTrue(schema.contains("\"adType\""));
		assertFalse(schema.contains("aiContentSummary"));
		assertFalse(schema.contains("contentsPattern"));
		assertFalse(schema.contains("aiCommentInsight"));
		assertFalse(schema.contains("commentAuthenticityGrade"));
		assertFalse(schema.contains("commentAuthenticityNote"));
	}

	/** 파트 A 유저 텍스트에는 지표·기준선·댓글 분포 줄이 없어야 한다(성숙 대기 이유가 이 세 줄이다). */
	@Test
	void 사실_유저텍스트에는_지표_기준선_댓글분포_줄이_없다() {
		String user = GeminiContentAnalyzer.userTextFacts(content("reels", true));

		assertTrue(user.contains("캡션A"));
		assertTrue(user.contains("인스타 유료 파트너십 태그: 있음"));
		assertFalse(user.contains("지표:"));
		assertFalse(user.contains("계정 기준선:"));
		assertFalse(user.contains("댓글 분류 분포:"));
	}

	/**
	 * Vertex 배치 출력에는 key가 없어 GeminiBatchLines가 에코된 유저 텍스트 첫 줄
	 * "콘텐츠: {shortCode} (" 에서 short_code를 복원한다. 파트 A 텍스트도 이 형식을 지켜야 한다.
	 */
	@Test
	void 사실_유저텍스트_첫줄은_에코_복원_형식을_지킨다() {
		String user = GeminiContentAnalyzer.userTextFacts(content("reels", true));

		assertTrue(user.startsWith("콘텐츠: post_a (@acct1, reels)"), user);
	}

	/** 파트 A 지시문에는 파트 B 규칙이 없고, 파트 A 규칙·분류표는 통합과 같은 문장을 공유한다. */
	@Test
	void 사실_지시문은_파트B_규칙을_빼고_파트A_규칙과_분류표는_공유한다() {
		String facts = GeminiContentAnalyzer.factsInstructions(taxonomy);
		String unified = GeminiContentAnalyzer.instructions(taxonomy);

		assertFalse(facts.contains("파트 B"), facts);
		assertFalse(facts.contains("aiContentSummary"), facts);
		assertTrue(facts.contains("detectedBrands"));
		assertTrue(facts.contains("fnb 축으로 분류하라"));
		assertTrue(facts.contains("공동구매(공구)"));
		assertTrue(facts.contains("클렌징폼/젤")); // 분류표
		// 파트 A 규칙 본문은 복제가 아니라 공유여야 한다 - 통합 프롬프트가 같은 문장을 담는다
		assertTrue(unified.contains(GeminiContentAnalyzer.FACTS_RULES
				.formatted(taxonomy.distributorsPrompt())));
	}

	/** 파트 A 파서는 속성만 돌려주고 sanitize(어휘 밖 제거·축 파생 is_beauty)를 통합과 공유한다. */
	@Test
	void parseFacts는_속성만_돌려주고_sanitize를_적용한다() {
		ContentAttributes attrs = GeminiContentAnalyzer.parseFacts(
				new tools.jackson.databind.ObjectMapper(), FACTS_RESPONSE, taxonomy);

		assertEquals("브랜드A", attrs.detectedBrands().get(0).name());
		assertEquals("cleansing", attrs.mainCategory());
		assertEquals(Boolean.TRUE, attrs.isRelevant());
		assertEquals(Boolean.TRUE, attrs.isBeauty()); // cleansing의 축이 beauty
		assertEquals(List.of("올리브영"), attrs.detectedDistributors());
	}

	/** 포트로 호출하면 파트 A 스키마·프롬프트로 1콜이 나간다(온라인 폴백 경로). */
	@Test
	void extractFacts는_파트A_스키마로_1콜을_보낸다() {
		ContentFactsPort port = new GeminiContentAnalyzer(fakeApi(FACTS_RESPONSE), () -> "m", () -> taxonomy);

		ContentAttributes attrs = port.extractFacts(content("reels", true), null);

		assertEquals(1, calls.size());
		assertFalse(calls.get(0).schema().contains("aiContentSummary"));
		assertFalse(calls.get(0).user().contains("계정 기준선:"));
		assertEquals("cleansing", attrs.mainCategory());
	}
}
