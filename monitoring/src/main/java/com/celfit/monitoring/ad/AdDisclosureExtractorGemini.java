package com.celfit.monitoring.ad;

import com.celfit.monitoring.llm.GeminiHttp;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 광고 표기 Tier2 — LLM은 <b>판단이 아니라 추출</b>만 한다(스펙 §5 Tier2·3 역할 분담 원리).
 * 사전·정규식이 못 잡는 표기 변형·신조어·부정 문맥(`광고 아니고 내돈내산`)의 문구를 찾아 그대로
 * 인용하고 카테고리만 분류한다 — 최종 verdict는 {@link AdVerdictCombiner}가 결정적으로 계산한다.
 *
 * <p>{@link com.celfit.monitoring.llm.BrandMentionJudge}와 달리 api-key 미설정·응답 파싱 실패를
 * fail-closed(UNCERTAIN)로 접지 않고 예외를 던진다 — 여기서 접으면 판정 컬럼에 잘못된 UNCERTAIN이
 * 영속화된다. 호출부({@link AdDisclosureJudgeService})가 예외를 잡아 verdict NULL을 유지하고
 * 다음 스윕이 재시도한다(스펙 §5).
 */
public class AdDisclosureExtractorGemini implements AdDisclosureExtractor {

	private static final String SYSTEM_INSTRUCTION = """
			너는 인스타그램 게시물 캡션에서 "경제적 이해관계(협찬·광고비 등 대가)를 받았다는 표시 문구"를
			찾아내는 추출기다. 판정은 하지 않는다 — 문구를 찾아 캡션 원문 그대로 인용하고 분류만 한다.

			분류 기준(공정거래위원회예규 제499호 「추천·보증 등에 관한 표시·광고 심사지침」 Ⅴ.6):
			- CLEAR(명확): 대가 수령 사실이 분명한 한국어 표현. 예: '#광고', '#유료광고', '#협찬',
			  '광고입니다', '유료 광고', '대가성 광고', '협찬받아 작성', '금전적 지원을 받았습니다',
			  '무료 상품을 제공받았습니다', '상품 협찬', '상품 할인을 제공받아 작성'.
			- AMBIGUOUS(모호): 대가 수령을 암시하지만 불명확하거나 소비자가 광고임을 알기 어려운 표현.
			  예: '체험 후기', '체험단', '선물', '~에서 보내주셨어요', 브랜드 해시태그 단순 언급
			  (광고·협찬 표시 없이 '#브랜드명'만), '브랜드명×계정명', 이해하기 어려운 줄임말.
			- FOREIGN(외국어 단독): 한국어 문맥 없이 외국어만으로 표기. 예: 'AD', 'PR', 'Sponsor',
			  'spon', 'sp', 'Collabo', '앰버서더', '땡스 투'. 단, 캡션 전체가 한국어 문장으로 자연스럽게
			  읽히면 FOREIGN이 아니라 CLEAR·AMBIGUOUS로 분류하라(예: "이 광고(AD)는 제가 직접...").
			- UNCERTAIN(판단불가): 표시로 보이지만 대가 수령 여부를 문맥만으로 판단하기 어려운 경우.

			부정 문맥 주의: '광고 아니고 내돈내산'처럼 광고임을 명시적으로 부정하는 문맥에서 등장한
			'광고'는 표시 문구가 아니다 — 추출하지 마라.

			phrase는 캡션에 실제로 등장하는 문자열 그대로(변형·요약·재구성 금지) 인용해야 한다.
			표시로 볼 수 있는 문구가 전혀 없으면 disclosures는 빈 배열이다.
			""";

	private final GeminiHttp http;
	private final String apiKey;
	private final String model;
	private final ObjectMapper om = new ObjectMapper();

	public AdDisclosureExtractorGemini(GeminiHttp http, String apiKey, String model) {
		this.http = http;
		this.apiKey = apiKey;
		this.model = model;
	}

	@Override
	public List<Disclosure> extract(String caption) {
		if (apiKey == null || apiKey.isBlank()) {
			throw new IllegalStateException("Gemini api-key 미설정 — 광고 표기 판정 불가(verdict NULL 유지)");
		}
		String responseBody = http.post("/v1beta/models/" + model + ":generateContent", requestBody(caption));
		return parse(responseBody);
	}

	private String requestBody(String caption) {
		ObjectNode root = om.createObjectNode();
		root.putObject("systemInstruction").putArray("parts").addObject().put("text", SYSTEM_INSTRUCTION);
		ArrayNode parts = root.putArray("contents").addObject().put("role", "user").putArray("parts");
		parts.addObject().put("text", "캡션:\n" + caption);
		ObjectNode gen = root.putObject("generationConfig");
		gen.put("temperature", 0);
		gen.put("responseMimeType", "application/json");
		gen.set("responseSchema", responseSchema());
		// BrandMentionJudge(단일 verdict, 256)의 2배 — 문구를 여러 건 그대로 인용해 담을 수 있어야 함
		gen.put("maxOutputTokens", 512);
		return om.writeValueAsString(root);
	}

	private ObjectNode responseSchema() {
		ObjectNode schema = om.createObjectNode();
		schema.put("type", "object");
		ObjectNode properties = schema.putObject("properties");
		ObjectNode disclosures = properties.putObject("disclosures");
		disclosures.put("type", "array");
		ObjectNode items = disclosures.putObject("items");
		items.put("type", "object");
		ObjectNode itemProps = items.putObject("properties");
		itemProps.putObject("phrase").put("type", "string");
		ObjectNode category = itemProps.putObject("category");
		category.put("type", "string");
		ArrayNode categoryEnum = category.putArray("enum");
		for (Category c : Category.values()) {
			categoryEnum.add(c.name());
		}
		items.putArray("required").add("phrase").add("category");
		schema.putArray("required").add("disclosures");
		return schema;
	}

	private List<Disclosure> parse(String responseBody) {
		JsonNode root = om.readTree(responseBody);
		JsonNode text = root.path("candidates").path(0).path("content").path("parts").path(0).path("text");
		if (text.isMissingNode()) {
			throw new IllegalStateException("Gemini 응답에 본문 없음: " + abbreviate(responseBody));
		}
		String textValue = text.asString();
		JsonNode innerRoot;
		try {
			innerRoot = om.readTree(textValue);
		} catch (JacksonException e) {
			// maxOutputTokens 초과로 JSON이 중간에 잘리는 경우가 대표 시나리오 — 원문 일부를 남겨 진단
			throw new IllegalStateException("응답 본문 JSON 파싱 실패: " + abbreviate(textValue), e);
		}
		JsonNode disclosures = innerRoot.path("disclosures");
		if (disclosures.isMissingNode() || !disclosures.isArray()) {
			throw new IllegalStateException("Gemini 응답에 disclosures 없음: " + abbreviate(textValue));
		}
		List<Disclosure> out = new ArrayList<>();
		for (JsonNode node : disclosures) {
			String phrase = node.path("phrase").asString();
			String categoryRaw = node.path("category").asString();
			Category category;
			try {
				category = Category.valueOf(categoryRaw);
			} catch (IllegalArgumentException e) {
				throw new IllegalStateException("예상 밖 category: " + categoryRaw, e);
			}
			out.add(new Disclosure(phrase, category));
		}
		return out;
	}

	private static String abbreviate(String s) {
		return s == null ? "(없음)" : s.length() > 300 ? s.substring(0, 300) + "…" : s;
	}
}
