package com.celfit.monitoring.llm;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 해시태그 발견 게시물의 브랜드 관련성 판정(스펙 2026-08-11 §4-6) — 핵심 역할은 이름 충돌 방어
 * (동명 타업종, 예: 도자기 "끌리메"). 캡션 기준 RELEVANT/UNCERTAIN/IRRELEVANT.
 * api-key 미설정이면 호출 없이 UNCERTAIN(비노출)로 접는다 — fail-closed.
 */
public class BrandMentionJudge {

	private static final Logger log = LoggerFactory.getLogger(BrandMentionJudge.class);

	public enum Verdict { RELEVANT, UNCERTAIN, IRRELEVANT }

	private static final String SYSTEM_INSTRUCTION = """
			너는 인스타그램 게시물이 특정 브랜드를 실제로 언급하는지 판정하는 심사관이다.
			같은 이름의 다른 업종·다른 제품(이름 충돌)이거나 우연히 태그만 달린 무관 게시물은 IRRELEVANT다.
			브랜드 소개(업종·제품군)가 주어지면 캡션 내용이 그 업종과 맞는지로 이름 충돌을 가려라.
			캡션이 없거나 정보가 부족해 판단할 수 없으면 UNCERTAIN이다.
			실제 방문 후기, 제품 리뷰, 협찬, 브랜드 소개처럼 해당 브랜드를 실제로 다루는 게시물만 RELEVANT다.
			캡션에 해당 브랜드를 다루는 정황이 있다면 확신이 완전하지 않아도 RELEVANT로 판정하라.
			반대로 캡션에서 브랜드 언급 자체가 확인되지 않으면 IRRELEVANT가 아니라 UNCERTAIN으로 판정하라.
			""";

	private final GeminiHttp http;
	private final String apiKey;
	private final String model;
	private final ObjectMapper om = new ObjectMapper();
	// 키 미설정 warn은 최초 1회만 — 게시물마다 호출되므로 이후는 debug로 접어 로그 스팸을 막는다
	private final AtomicBoolean missingKeyWarned = new AtomicBoolean(false);

	public BrandMentionJudge(GeminiHttp http, String apiKey, String model) {
		this.http = http;
		this.apiKey = apiKey;
		this.model = model;
	}

	/** brandBiography는 이름 충돌 방어의 실질 근거(스펙 §4-6 "업종" 컨텍스트) — null 허용. */
	public Verdict judge(String brandUsername, String brandBiography, List<String> brandTags,
			String authorUsername, String caption) {
		if (apiKey == null || apiKey.isBlank()) {
			// fail-closed: 키 미설정 환경(로컬 등)에서 스윕을 죽이지 않고 비노출로 접는다 — 운영에서
			// 조용히 전 판정이 UNCERTAIN으로 접히면 알아챌 방법이 없으므로 최초 1회는 warn으로 남긴다
			if (missingKeyWarned.compareAndSet(false, true)) {
				log.warn("Gemini api-key 미설정 — 해시태그 판정을 UNCERTAIN(비노출)으로 접는다");
			} else {
				log.debug("Gemini api-key 미설정 — 해시태그 판정을 UNCERTAIN(비노출)으로 접는다");
			}
			return Verdict.UNCERTAIN;
		}
		String userText = userText(brandUsername, brandBiography, brandTags, authorUsername, caption);
		String body = requestBody(userText);
		String responseBody = http.post("/v1beta/models/" + model + ":generateContent", body);
		return parseVerdict(responseBody);
	}

	private static String userText(String brandUsername, String brandBiography, List<String> brandTags,
			String authorUsername, String caption) {
		String captionText = (caption == null || caption.isBlank()) ? "(캡션 없음)" : caption;
		StringBuilder sb = new StringBuilder();
		sb.append("브랜드 계정: @").append(brandUsername).append('\n');
		if (brandBiography != null && !brandBiography.isBlank()) {
			sb.append("브랜드 소개: ").append(brandBiography).append('\n');
		}
		sb.append("브랜드 해시태그: ").append(String.join(", ", brandTags)).append('\n');
		sb.append("게시자: @").append(authorUsername).append('\n');
		sb.append("캡션: ").append(captionText).append('\n');
		return sb.toString();
	}

	private String requestBody(String userText) {
		ObjectNode root = om.createObjectNode();
		root.putObject("systemInstruction").putArray("parts").addObject().put("text", SYSTEM_INSTRUCTION);
		ArrayNode parts = root.putArray("contents").addObject().put("role", "user").putArray("parts");
		parts.addObject().put("text", userText);
		ObjectNode gen = root.putObject("generationConfig");
		gen.put("temperature", 0);
		gen.put("responseMimeType", "application/json");
		gen.set("responseSchema", responseSchema());
		gen.put("maxOutputTokens", 256);
		return om.writeValueAsString(root);
	}

	private ObjectNode responseSchema() {
		ObjectNode schema = om.createObjectNode();
		schema.put("type", "object");
		ObjectNode properties = schema.putObject("properties");
		ObjectNode verdict = properties.putObject("verdict");
		verdict.put("type", "string");
		ArrayNode enumValues = verdict.putArray("enum");
		for (Verdict v : Verdict.values()) {
			enumValues.add(v.name());
		}
		schema.putArray("required").add("verdict");
		return schema;
	}

	private Verdict parseVerdict(String responseBody) {
		JsonNode root = om.readTree(responseBody);
		JsonNode text = root.path("candidates").path(0).path("content").path("parts").path(0).path("text");
		if (text.isMissingNode()) {
			throw new IllegalStateException("Gemini 응답에 본문 없음: " + abbreviate(responseBody));
		}
		JsonNode verdictNode = om.readTree(text.asString()).path("verdict");
		if (verdictNode.isMissingNode()) {
			throw new IllegalStateException("Gemini 응답에 verdict 없음: " + abbreviate(text.asString()));
		}
		String verdict = verdictNode.asString();
		try {
			return Verdict.valueOf(verdict);
		} catch (IllegalArgumentException e) {
			throw new IllegalStateException("예상 밖 Gemini verdict: " + verdict, e);
		}
	}

	private static String abbreviate(String s) {
		return s == null ? "(없음)" : s.length() > 300 ? s.substring(0, 300) + "…" : s;
	}
}
