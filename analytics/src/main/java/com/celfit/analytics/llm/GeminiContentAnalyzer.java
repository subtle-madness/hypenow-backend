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
public final class GeminiContentAnalyzer implements ContentInsightPort {

	private static final Set<String> GRADES = Set.of("high", "normal", "suspect");
	public static final int MAX_OUTPUT_TOKENS = 4096;

	/** 통합 산출 스키마 — 속성 11필드(nullable) + 종합 5필드. Gemini responseSchema(OpenAPI 스타일). */
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
			  "detectedProductCategories","detectedProducts","vlmAttributes","mainCategory","subCategories",
			  "detectedDistributors","adType","aiContentSummary","contentsPattern","aiCommentInsight",
			  "commentAuthenticityGrade","commentAuthenticityNote"]}""";

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

	/** 통합 시스템 프롬프트 — 속성 지침(골드셋 검증) + 종합 지침 + 절제 규칙 + 분류표. */
	public static String instructions(BeautyTaxonomy taxonomy) {
		return """
				당신은 뷰티 브랜드 마케터를 위한 인스타그램 콘텐츠 분석가다. 캡션(과 썸네일이 주어지면 썸네일)과
				지표를 보고 [A] 콘텐츠 속성과 [B] 종합 해석을 한 번에 산출하라. 한국어로.

				[A. 속성 — 캡션·썸네일 근거. 확신이 없는 항목은 null 또는 빈 배열로 두고 지어내지 마라]
				- detectedBrands: 캡션·화면에서 확인되는 브랜드 {name, evidence(근거)} —
				  브랜드를 특정할 수 없는 제품은 목록에서 제외하라 ("미상"/"불명확" 같은 표기 금지)
				- sponsoredSignalLevel: 광고성 high|mid|low, sponsoredSignalReasons: 근거 나열
				- adDisclosure: 광고 고지 여부 (예: "캡션 #협찬 표기 있음", 없으면 "표기 없음")
				- mainCategory: 아래 분류표의 대분류 영문 값 중 하나
				- subCategories: 이 콘텐츠에 해당하는 중분류·소분류 라벨 전부 — 분류표의 표기 그대로
				  (예: 립틴트 콘텐츠면 ["립메이크업","립틴트"])
				- detectedProductCategories: 확인되는 제품들의 소분류 라벨 — 분류표의 표기 그대로
				- detectedProducts: 확인되는 제품명 {name(상품명), brand(그 제품의 브랜드, 미상이면 null)}
				- detectedDistributors: 확인되는 유통 채널 — %s 만, 그 외 상호는 제외
				- vlmAttributes: {label, value} — 노출 제품 / 제품 노출 비중 / 후킹 요소 / 전환 장치 /
				  콘텐츠 유형 / 무드 / 편집 스타일 순 (썸네일 없이 판단 불가한 항목은 제외)
				- adType: organic|sponsored (캡션 표기+화면 종합 판정)

				[B. 종합 — 주어진 수치만 근거로 삼고 수치를 지어내지 마라. 각 항목 2~3문장 이내]
				- aiContentSummary: 이 콘텐츠가 계정 평균 대비 어땠는지(배수·순위), 반응의 성격(구매 전환형/화제성),
				  협찬 수용도를 종합한 요약
				- contentsPattern: 이 계정의 어떤 콘텐츠 패턴에서 성과가 나는지 한 줄 해석
				- aiCommentInsight: 댓글 분포 수치를 근거로 반응의 질을 해석
				- commentAuthenticityGrade: high(자연스러운 반응) | normal | suspect(도배·기계적 패턴 의심)
				- commentAuthenticityNote: 판정 근거 한 줄

				%s

				[분류표 — 대분류(한글): 중분류[소분류, …]]
				%s""".formatted(taxonomy.distributorsPrompt(), LlmGuard.RULES, taxonomy.promptTable());
	}

	/** 유저 입력 — 기존 AnthropicSynthesizer 입력 포맷 그대로 (골드셋 문구 검증도 이 포맷). */
	public static String userText(ContentToAnalyze c, boolean withThumbnail) {
		return """
				콘텐츠: %s (@%s, %s)
				캡션: %s
				지표: views=%s likes=%s comments=%s
				계정 기준선: %s
				댓글 분류 분포: %s

				위 %s과 지표를 분석하라.""".formatted(c.shortCode(), c.accountHandle(), c.contentType(),
				c.caption() == null ? "(없음)" : c.caption(), c.views(), c.likes(), c.comments(),
				c.baseline(), c.commentCategoryCounts(), withThumbnail ? "썸네일·캡션" : "캡션");
	}

	@Override
	public ContentInsight analyze(ContentToAnalyze content, String thumbnailUrl) {
		BeautyTaxonomy tx = taxonomy.get();
		GeminiApi.InlineImage image = thumbnailUrl == null ? null : download(thumbnailUrl);
		String out = api.generateJson(model.get(), instructions(tx),
				userText(content, image != null), image, RESPONSE_SCHEMA, MAX_OUTPUT_TOKENS);
		return parse(om, out, tx);
	}

	/** 응답 JSON → 속성(sanitize)+종합(등급 방어). 백필 러너(Batch 결과 파싱)도 이 진입점을 쓴다. */
	public static ContentInsight parse(ObjectMapper om, String json, BeautyTaxonomy taxonomy) {
		Output o = om.readValue(json, Output.class);
		ContentAttributes attrs = AnthropicContentAttributeAnalyzer.sanitize(new ContentAttributes(
				o.detectedBrands(), o.sponsoredSignalLevel(), o.sponsoredSignalReasons(), o.adDisclosure(),
				o.detectedProductCategories(), o.detectedProducts(), o.vlmAttributes(), o.mainCategory(),
				o.subCategories(), o.detectedDistributors(), o.adType()), taxonomy);
		String grade = o.commentAuthenticityGrade() != null && GRADES.contains(o.commentAuthenticityGrade())
				? o.commentAuthenticityGrade() : "normal";
		return new ContentInsight(attrs, new Synthesis(o.aiContentSummary(), o.contentsPattern(),
				o.aiCommentInsight(), grade, o.commentAuthenticityNote()));
	}

	/** 통합 산출 합본 — RESPONSE_SCHEMA와 1:1. */
	record Output(List<ContentAttributes.Brand> detectedBrands, String sponsoredSignalLevel,
			List<String> sponsoredSignalReasons, String adDisclosure,
			List<String> detectedProductCategories, List<ContentAttributes.Product> detectedProducts,
			List<ContentAttributes.Attribute> vlmAttributes, String mainCategory,
			List<String> subCategories, List<String> detectedDistributors, String adType,
			String aiContentSummary, String contentsPattern, String aiCommentInsight,
			String commentAuthenticityGrade, String commentAuthenticityNote) {}

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
