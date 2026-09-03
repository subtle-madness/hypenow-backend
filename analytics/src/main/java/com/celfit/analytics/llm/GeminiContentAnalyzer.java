package com.celfit.analytics.llm;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import tools.jackson.databind.ObjectMapper;

/**
 * ②속성+③종합 통합 Gemini 어댑터 — 1콜로 ContentAttributes 11필드 + Synthesis 5필드 합본을 받는다
 * (2026-07-18 확정). 스키마·호출 형태는 골드셋 스파이크(run_gemini.py) 검증본, 어휘 sanitize는
 * Anthropic 속성 어댑터와 동일 로직 공유. 썸네일은 직접 다운로드 후 inlineData(base64).
 */
public final class GeminiContentAnalyzer implements ContentInsightPort, ContentFactsPort {

	private static final Set<String> GRADES = Set.of("high", "normal", "suspect");
	public static final int MAX_OUTPUT_TOKENS = 8192; // 검증 하니스(run_unified.py) 동일값 — 16필드 합본 여유

	/**
	 * 통합 산출 스키마 — 속성 11필드(nullable) + 종합 5필드. Gemini responseSchema(OpenAPI 스타일).
	 * propertyOrdering으로 사실 추출(파트 A) → 해석(파트 B) 생성 순서를 강제한다 (검증 하니스 동일).
	 */
	public static final String RESPONSE_SCHEMA = """
			{"type":"object","properties":{
			  "detectedBrands":{"type":"array","nullable":true,"items":{"type":"object",
			    "properties":{"name":{"type":"string"},"evidence":{"type":"string","nullable":true}},
			    "required":["name","evidence"]}},
			  "sponsoredSignalLevel":{"type":"string","nullable":true},
			  "sponsoredSignalReasons":{"type":"array","nullable":true,"items":{"type":"string"}},
			  "adDisclosure":{"type":"string","nullable":true},
			  "detectedProductCategories":{"type":"array","nullable":true,"items":{"type":"string"}},
			  "detectedProducts":{"type":"array","nullable":true,"items":{"type":"object",
			    "properties":{"name":{"type":"string"},"brand":{"type":"string","nullable":true}},
			    "required":["name","brand"]}},
			  "vlmAttributes":{"type":"array","nullable":true,"items":{"type":"object",
			    "properties":{"label":{"type":"string"},"value":{"type":"string"}},
			    "required":["label","value"]}},
			  "isRelevant":{"type":"boolean"},
			  "mainCategory":{"type":"string","nullable":true},
			  "subCategories":{"type":"array","nullable":true,"items":{"type":"string"}},
			  "detectedDistributors":{"type":"array","nullable":true,"items":{"type":"string"}},
			  "adType":{"type":"string","nullable":true},
			  "aiContentSummary":{"type":"string"},
			  "contentsPattern":{"type":"string"},
			  "aiCommentInsight":{"type":"string"},
			  "commentAuthenticityGrade":{"type":"string"},
			  "commentAuthenticityNote":{"type":"string"}},
			 "required":["detectedBrands","sponsoredSignalLevel","sponsoredSignalReasons","adDisclosure",
			  "detectedProductCategories","detectedProducts","vlmAttributes","isRelevant","mainCategory","subCategories",
			  "detectedDistributors","adType","aiContentSummary","contentsPattern","aiCommentInsight",
			  "commentAuthenticityGrade","commentAuthenticityNote"],
			 "propertyOrdering":["detectedBrands","sponsoredSignalLevel","sponsoredSignalReasons","adDisclosure",
			  "detectedProductCategories","detectedProducts","vlmAttributes","isRelevant","mainCategory","subCategories",
			  "detectedDistributors","adType","aiContentSummary","contentsPattern","aiCommentInsight",
			  "commentAuthenticityGrade","commentAuthenticityNote"]}""";

	/**
	 * 파트 A(사실) 전용 스키마 - 통합 스키마({@link #RESPONSE_SCHEMA})에서 해석 5필드
	 * (aiContentSummary·contentsPattern·aiCommentInsight·commentAuthenticityGrade·
	 * commentAuthenticityNote)를 properties·required·propertyOrdering 세 곳에서 뺀 것이다.
	 * 나머지 11필드의 정의·순서는 통합과 완전히 같다(2026-09-03 2단계 분리 설계 §4-4).
	 */
	public static final String RESPONSE_SCHEMA_FACTS = """
			{"type":"object","properties":{
			  "detectedBrands":{"type":"array","nullable":true,"items":{"type":"object",
			    "properties":{"name":{"type":"string"},"evidence":{"type":"string","nullable":true}},
			    "required":["name","evidence"]}},
			  "sponsoredSignalLevel":{"type":"string","nullable":true},
			  "sponsoredSignalReasons":{"type":"array","nullable":true,"items":{"type":"string"}},
			  "adDisclosure":{"type":"string","nullable":true},
			  "detectedProductCategories":{"type":"array","nullable":true,"items":{"type":"string"}},
			  "detectedProducts":{"type":"array","nullable":true,"items":{"type":"object",
			    "properties":{"name":{"type":"string"},"brand":{"type":"string","nullable":true}},
			    "required":["name","brand"]}},
			  "vlmAttributes":{"type":"array","nullable":true,"items":{"type":"object",
			    "properties":{"label":{"type":"string"},"value":{"type":"string"}},
			    "required":["label","value"]}},
			  "isRelevant":{"type":"boolean"},
			  "mainCategory":{"type":"string","nullable":true},
			  "subCategories":{"type":"array","nullable":true,"items":{"type":"string"}},
			  "detectedDistributors":{"type":"array","nullable":true,"items":{"type":"string"}},
			  "adType":{"type":"string","nullable":true}},
			 "required":["detectedBrands","sponsoredSignalLevel","sponsoredSignalReasons","adDisclosure",
			  "detectedProductCategories","detectedProducts","vlmAttributes","isRelevant","mainCategory","subCategories",
			  "detectedDistributors","adType"],
			 "propertyOrdering":["detectedBrands","sponsoredSignalLevel","sponsoredSignalReasons","adDisclosure",
			  "detectedProductCategories","detectedProducts","vlmAttributes","isRelevant","mainCategory","subCategories",
			  "detectedDistributors","adType"]}""";

	private final GeminiApi api;
	private final Supplier<String> model;
	private final Supplier<BeautyTaxonomy> taxonomy;
	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
	private final ObjectMapper om = new ObjectMapper();

	public GeminiContentAnalyzer(GeminiApi api, Supplier<String> model, Supplier<BeautyTaxonomy> taxonomy) {
		this.api = api;
		this.model = model;
		this.taxonomy = taxonomy;
	}

	/**
	 * 파트 A(캡션 속성 추출) 규칙 - 통합 콜과 사실 전용 콜({@link #factsInstructions})이
	 * <b>공유</b>한다. 복제해 두면 한쪽만 고쳐져 통합 분석분과 파트 A 분석분의 판정 기준이 갈린다
	 * (07-21 계정 카피 프롬프트 복제 사고와 같은 함정 - SYNTHESIS_RULES 주석 참조).
	 * {@code %s} 자리는 {@code taxonomy.distributorsPrompt()}다.
	 */
	static final String FACTS_RULES = """
			[파트 A — 캡션 속성 추출]
			캡션(과 썸네일이 주어지면 썸네일)에서 다음을 추출하라.
			확신이 없는 항목은 null 또는 빈 배열로 두고 지어내지 마라.
			분류표의 어느 대분류에도 해당하지 않으면 mainCategory는 null이다.

			- isRelevant: 이 콘텐츠가 분류표의 대분류 중 하나에 해당하는가 (true/false).
			  제품·시술·루틴·리뷰·요리 등이면 true, 인플루언서가 뷰티·F&B라도 무관한
			  일상·여행·반려동물 등이면 false. mainCategory와 독립적으로 반드시 판정하라.
			- detectedBrands: 캡션·화면에서 확인되는 브랜드 {name, evidence(근거)} —
			  브랜드를 특정할 수 없는 제품은 목록에서 제외하라 ("미상"/"불명확" 같은 표기 금지)
			- sponsoredSignalLevel: 광고성 high|mid|low, sponsoredSignalReasons: 근거 나열
			- adDisclosure: 광고 고지 여부 (예: "캡션 #협찬 표기 있음", 없으면 "표기 없음")
			- mainCategory: 아래 분류표의 대분류 영문 값 중 하나. 분류표는 [축] 표기로 계열을 밝힌다.
			  ※ 섭취하는 제품(건강기능식품·단백질·다이어트 식품·이너뷰티 포함)은 뷰티 목적이어도
			    fnb 축으로 분류하라 — 제형이 아니라 섭취 여부가 기준이다.
			- subCategories: 이 콘텐츠에 해당하는 중분류·소분류 라벨 전부 — 분류표의 표기 그대로.
			  중분류만 적고 끝내지 마라 — 해당하는 소분류(각 중분류의 대괄호 안 라벨)까지 반드시
			  포함하라. 소분류를 하나도 특정할 수 없을 때만 중분류 단독을 허용한다.
			  (예: 립틴트 콘텐츠면 ["립메이크업","립틴트"], 밀키트 소개면 ["가공/간편식","밀키트"])
			- detectedProductCategories: 확인되는 제품들의 소분류 라벨 — 분류표의 표기 그대로
			- detectedProducts: 확인되는 제품명 {name(상품명), brand(그 제품의 브랜드, 미상이면 null)}
			- detectedDistributors: 확인되는 유통 채널 — %s 만, 그 외 상호는 제외.
			  괄호 안은 그 유통사가 속한 축이다 — mainCategory와 같은 축의 유통사만 답하라.
			- vlmAttributes: {label, value} — 노출 제품 / 제품 노출 비중 / 후킹 요소 / 전환 장치 /
			  콘텐츠 유형 / 무드 / 편집 스타일 순 (썸네일 없이 판단 불가한 항목은 제외)
			- adType: organic|sponsored (캡션 표기+화면 종합 판정)
			  ※ 입력의 "인스타 유료 파트너십 태그"가 '있음'이면 인스타가 보증하는 확정 광고다 —
			    이 경우 adType은 반드시 sponsored로 판정하라. 반대로 '없음'은 광고가 아니라는 뜻이 아니다
			    (피드는 태그 기능 자체가 없고, 릴스도 캡션으로만 고지하는 경우가 많다) —
			    '없음'·'해당 없음'이면 캡션·화면 근거로 평소대로 판단하라.
			  ※ 공동구매(공구)는 인플루언서가 대가를 받고 판매하는 상업 콘텐츠다 —
			    sponsored로 판정하라.""";

	/**
	 * 파트 B(성과 종합) 문구 정의 — 통합 콜과 해석 문구 전용 어댑터({@link GeminiContentSynthesizer})가
	 * <b>공유</b>한다. 복제해 두면 한쪽만 고쳐져 신규 분석분과 재생성분의 의미가 갈린다.
	 * 이 문장이 바뀌면 {@link Synthesis#VERSION}을 올려 기존 행을 갱신 대상으로 만들 것.
	 */
	static final String SYNTHESIS_RULES = """
			[파트 B — 성과 종합]
			추출된 사실과 입력의 지표·기준선·댓글 분포 수치만 근거로 삼고 수치를 지어내지 마라.
			각 항목 2~3문장 이내.
			기준선 키의 접미사 _pct는 이미 퍼센트 값이다(화면 표기와 동일) — 인용할 때 100을 곱하거나
			나누지 말고 그대로 쓰고 % 를 붙여라.

			- aiContentSummary: 이 콘텐츠가 계정 평균 대비 어땠는지(배수·순위), 반응의 성격(구매 전환형/화제성),
			  협찬 수용도를 종합한 요약
			- contentsPattern: 이 콘텐츠가 최근 12개 평균(계정 기준선) 대비 참여율·좋아요·댓글에서 어느 지점이 두드러지거나 처지는지 한 줄 비교. 기준선 수치를 인용하고, 계정 전체 콘텐츠 경향으로 일반화하지 마라 (07-21 재정의: 콘텐츠 1건 입력으로 계정 패턴을 물어 표본부족 헤지가 나던 것을 소비처 Comparison.narrative에 맞춰 비교 해설로 낮춤)
			- aiCommentInsight: 댓글 분포 수치를 근거로 반응의 질을 해석
			- commentAuthenticityGrade: high(자연스러운 반응) | normal | suspect(도배·기계적 패턴 의심)
			- commentAuthenticityNote: 판정 근거 한 줄""";

	/** 통합 시스템 프롬프트 — 검증 통과본(unified_prompt.txt, 07-18 11건 검증) verbatim. */
	public static String instructions(BeautyTaxonomy taxonomy) {
		return """
				당신은 브랜드 마케터를 위한 인스타그램 콘텐츠 분석가다. 한 번의 분석에서
				[파트 A] 캡션 속성 추출과 [파트 B] 성과 종합을 함께 수행한다. 한국어로 답한다.

				%s

				%s

				[파트 B 절제 규칙 — 반드시 지켜라]
				%s

				[분류표 — [축] 대분류(한글): 중분류[소분류, …]]
				%s""".formatted(FACTS_RULES.formatted(taxonomy.distributorsPrompt()),
			SYNTHESIS_RULES, LlmGuard.BODY, taxonomy.promptTable());
	}

	/**
	 * 파트 A(사실) 전용 시스템 프롬프트 - 통합에서 파트 B 규칙과 절제 규칙 블록만 뺐다.
	 * 파트 A 규칙 본문·분류표는 {@link #FACTS_RULES}·{@code taxonomy.promptTable()}로 통합과 공유한다.
	 * LlmGuard(절제 규칙)를 싣지 않는 이유: 그 규칙은 문구 생성(해석)의 과장·조언을 막는 장치라
	 * 사실 추출에는 적용 대상이 없다. 파트 B 콜이 그대로 싣는다.
	 */
	public static String factsInstructions(BeautyTaxonomy taxonomy) {
		return """
				당신은 브랜드 마케터를 위한 인스타그램 콘텐츠 분석가다.
				캡션 속성 추출만 수행한다 - 성과 해석·종합 문구는 다른 단계에서 만든다. 한국어로 답한다.

				%s

				[분류표 - [축] 대분류(한글): 중분류[소분류, ...]]
				%s""".formatted(FACTS_RULES.formatted(taxonomy.distributorsPrompt()), taxonomy.promptTable());
	}

	/** 유저 입력 — 검증 하니스(run_unified.py) 포맷 그대로 + 공식 광고태그 사실 1줄. */
	public static String userText(ContentToAnalyze c) {
		return """
				콘텐츠: %s (@%s, %s)
				캡션: %s
				인스타 유료 파트너십 태그: %s
				지표: views=%s likes=%s comments=%s
				계정 기준선: %s
				댓글 분류 분포: %s

				위 콘텐츠를 분석하라.""".formatted(c.shortCode(), c.accountHandle(), c.contentType(),
				c.caption() == null ? "(없음)" : c.caption(), adMarkedText(c),
				c.views(), c.likes(), c.comments(), c.baseline(), c.commentCategoryCounts());
	}

	/**
	 * 파트 A 유저 입력 - 통합({@link #userText})에서 지표·계정 기준선·댓글 분포 3줄을 뺐다.
	 * 그 세 줄이 성숙(D+4)을 기다리게 하던 유일한 이유이므로, 빼면 D+1에 돌 수 있다.
	 *
	 * <p>첫 줄 형식은 통합과 반드시 같아야 한다 - Vertex 배치 출력에는 key가 없어
	 * {@code GeminiBatchLines.shortCodeFromEcho}가 에코된 "콘텐츠: {shortCode} (" 에서
	 * short_code를 복원한다.
	 */
	public static String userTextFacts(ContentToAnalyze c) {
		return """
				콘텐츠: %s (@%s, %s)
				캡션: %s
				인스타 유료 파트너십 태그: %s

				위 콘텐츠의 캡션 속성을 추출하라.""".formatted(c.shortCode(), c.accountHandle(),
				c.contentType(), c.caption() == null ? "(없음)" : c.caption(), adMarkedText(c));
	}

	/**
	 * 태그는 릴스 전용 기능이라 판정 기준은 값이 아니라 콘텐츠 타입이다 — 미러(v_contents)는 피드에
	 * false를 채우므로 값만 보면 "없음"이 되어 "광고 아님"으로 오독된다. 릴스인데 값이 없는 경우
	 * (이례적)는 "없음"이 아니라 "확인 안 됨"으로 구분해 근거 없는 organic 판정을 막는다.
	 */
	private static String adMarkedText(ContentToAnalyze c) {
		if (!"reels".equalsIgnoreCase(c.contentType())) {
			return "해당 없음 (피드는 태그 기능 없음)";
		}
		if (c.adMarked() == null) {
			return "확인 안 됨";
		}
		return c.adMarked() ? "있음 (인스타 공식 표기 — 확정 광고)" : "없음";
	}

	@Override
	public ContentInsight analyze(ContentToAnalyze content, String thumbnailUrl) {
		BeautyTaxonomy tx = taxonomy.get();
		GeminiApi.InlineImage image = thumbnailUrl == null ? null : download(thumbnailUrl);
		String out = api.generateJson(model.get(), instructions(tx),
				userText(content), image, RESPONSE_SCHEMA, MAX_OUTPUT_TOKENS);
		return parse(om, out, tx);
	}

	@Override
	public ContentAttributes extractFacts(ContentToAnalyze content, String thumbnailUrl) {
		BeautyTaxonomy tx = taxonomy.get();
		GeminiApi.InlineImage image = thumbnailUrl == null ? null : download(thumbnailUrl);
		String out = api.generateJson(model.get(), factsInstructions(tx),
				userTextFacts(content), image, RESPONSE_SCHEMA_FACTS, MAX_OUTPUT_TOKENS);
		return parseFacts(om, out, tx);
	}

	/** 응답 JSON → 속성(sanitize)+종합(등급 방어). 백필 러너(Batch 결과 파싱)도 이 진입점을 쓴다. */
	public static ContentInsight parse(ObjectMapper om, String json, BeautyTaxonomy taxonomy) {
		Output o = om.readValue(json, Output.class);
		ContentAttributes attrs = AnthropicContentAttributeAnalyzer.sanitize(new ContentAttributes(
				o.detectedBrands(), o.sponsoredSignalLevel(), o.sponsoredSignalReasons(), o.adDisclosure(),
				o.detectedProductCategories(), o.detectedProducts(), o.vlmAttributes(), o.mainCategory(),
				o.subCategories(), o.detectedDistributors(), o.adType(), o.isRelevant(), null), taxonomy);
		String grade = defendGrade(o.commentAuthenticityGrade());
		return new ContentInsight(attrs, new Synthesis(o.aiContentSummary(), o.contentsPattern(),
				o.aiCommentInsight(), grade, o.commentAuthenticityNote()));
	}

	/**
	 * 파트 A 응답 JSON → 속성(sanitize). 어휘 밖 값 제거·축 파생 is_beauty는 통합 파서와 같은
	 * {@code AnthropicContentAttributeAnalyzer.sanitize}를 공유한다 - 통합 분석분과 파트 A
	 * 분석분의 저장 값이 갈리지 않게 하는 지점이다.
	 */
	public static ContentAttributes parseFacts(ObjectMapper om, String json, BeautyTaxonomy taxonomy) {
		FactsOutput o = om.readValue(json, FactsOutput.class);
		return AnthropicContentAttributeAnalyzer.sanitize(new ContentAttributes(
				o.detectedBrands(), o.sponsoredSignalLevel(), o.sponsoredSignalReasons(), o.adDisclosure(),
				o.detectedProductCategories(), o.detectedProducts(), o.vlmAttributes(), o.mainCategory(),
				o.subCategories(), o.detectedDistributors(), o.adType(), o.isRelevant(), null), taxonomy);
	}

	/** 종합 5필드만 파싱 — 해석 문구 전용 어댑터({@link GeminiContentSynthesizer})가 쓴다. 등급 방어 공유. */
	public static Synthesis parseSynthesis(ObjectMapper om, String json) {
		SynthesisOutput o = om.readValue(json, SynthesisOutput.class);
		return new Synthesis(o.aiContentSummary(), o.contentsPattern(), o.aiCommentInsight(),
				defendGrade(o.commentAuthenticityGrade()), o.commentAuthenticityNote());
	}

	/** 어휘 밖 등급은 normal로 — 통합 파서와 같은 방어. */
	private static String defendGrade(String grade) {
		return grade != null && GRADES.contains(grade) ? grade : "normal";
	}

	record SynthesisOutput(String aiContentSummary, String contentsPattern, String aiCommentInsight,
			String commentAuthenticityGrade, String commentAuthenticityNote) {}

	/** 통합 산출 합본 — RESPONSE_SCHEMA와 1:1. */
	record Output(List<ContentAttributes.Brand> detectedBrands, String sponsoredSignalLevel,
			List<String> sponsoredSignalReasons, String adDisclosure,
			List<String> detectedProductCategories, List<ContentAttributes.Product> detectedProducts,
			List<ContentAttributes.Attribute> vlmAttributes, Boolean isRelevant, String mainCategory,
			List<String> subCategories, List<String> detectedDistributors, String adType,
			String aiContentSummary, String contentsPattern, String aiCommentInsight,
			String commentAuthenticityGrade, String commentAuthenticityNote) {}

	/** 파트 A 산출 - RESPONSE_SCHEMA_FACTS와 1:1(해석 5필드 없음). */
	record FactsOutput(List<ContentAttributes.Brand> detectedBrands, String sponsoredSignalLevel,
			List<String> sponsoredSignalReasons, String adDisclosure,
			List<String> detectedProductCategories, List<ContentAttributes.Product> detectedProducts,
			List<ContentAttributes.Attribute> vlmAttributes, Boolean isRelevant, String mainCategory,
			List<String> subCategories, List<String> detectedDistributors, String adType) {}

	/** 썸네일 직접 다운로드 — 잡의 HEAD 프리체크 생존분만 들어온다. 실패는 콘텐츠 실패(다음 실행 재대상). */
	private GeminiApi.InlineImage download(String thumbnailUrl) {
		try {
			HttpRequest req = HttpRequest.newBuilder(URI.create(thumbnailUrl))
					.timeout(Duration.ofSeconds(15)).build();
			HttpResponse<byte[]> res = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
			if (res.statusCode() < 200 || res.statusCode() >= 300) {
				throw new IllegalStateException("썸네일 다운로드 실패 HTTP " + res.statusCode());
			}
			String mime = res.headers().firstValue("content-type").orElse("image/jpeg");
			return new GeminiApi.InlineImage(mime.split(";")[0].trim(), res.body());
		} catch (java.io.IOException | InterruptedException e) {
			throw new IllegalStateException("썸네일 다운로드 실패: " + thumbnailUrl, e);
		}
	}
}
