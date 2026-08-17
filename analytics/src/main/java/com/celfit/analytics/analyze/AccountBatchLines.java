package com.celfit.analytics.analyze;

import com.celfit.analytics.llm.GeminiAccountSynthesizer;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 계정 카피 배치 JSONL 조립·결과 해석 헬퍼 — 콘텐츠의 {@link GeminiBatchLines} 동형(2026-08-17).
 * 콘텐츠와 달리 시스템 지시문이 계정별(PerfConfidence 판정 포함)이라 라인마다 받고, 사이드카는
 * 기준선 대신 저장 시점 복원용 3필드(last_posted_at·analyzed_count·ad_situation)만 싣는다.
 */
final class AccountBatchLines {

	/** 사이드카 키 — AccountAnalysisWriter.insert가 LLM 응답 외에 요구하는 제출 시점 스냅샷. */
	static final List<String> SIDECAR_KEYS = List.of("last_posted_at", "analyzed_count", "ad_situation");

	/** GeminiAccountSynthesizer.userText 첫 줄("계정: @{handle} (…")에서 핸들 복원. */
	private static final java.util.regex.Pattern ECHO_HANDLE =
			java.util.regex.Pattern.compile("^계정: @(\\S+) \\(");

	private AccountBatchLines() {
	}

	/** JSONL 요청 라인 — key=handle. 시스템 지시문은 계정별(confidence 포함)이라 호출자가 조립해 넘긴다. */
	static ObjectNode requestLine(ObjectMapper om, String handle, String system, String userText) {
		ObjectNode line = om.createObjectNode();
		line.put("key", handle);
		ObjectNode request = line.putObject("request");
		request.putObject("systemInstruction").putArray("parts").addObject().put("text", system);
		request.putArray("contents").addObject().put("role", "user").putArray("parts")
				.addObject().put("text", userText);
		ObjectNode gen = request.putObject("generationConfig");
		gen.put("temperature", 0);
		gen.put("responseMimeType", "application/json");
		gen.set("responseSchema", om.readTree(GeminiAccountSynthesizer.RESPONSE_SCHEMA));
		gen.put("maxOutputTokens", GeminiAccountSynthesizer.MAX_OUTPUT_TOKENS);
		return line;
	}

	/** 사이드카 라인 — 수거 시점에 AccountAnalysisWriter.insert 인자를 복원하기 위한 기록. */
	static ObjectNode sidecarLine(ObjectMapper om, String handle, OffsetDateTime lastPostedAt,
			Long analyzedCount, String adSituationName) {
		ObjectNode line = om.createObjectNode();
		line.put("handle", handle);
		if (lastPostedAt == null) {
			line.putNull("last_posted_at");
		} else {
			line.put("last_posted_at", lastPostedAt.toString());
		}
		if (analyzedCount == null) {
			line.putNull("analyzed_count");
		} else {
			line.put("analyzed_count", analyzedCount.toString());
		}
		line.put("ad_situation", adSituationName);
		return line;
	}

	/** 사이드카 JSONL 파싱 — handle → 필드맵. GeminiBatchLines.parseSidecar와 동형. */
	static Map<String, Map<String, String>> parseSidecar(ObjectMapper om, String contents) {
		Map<String, Map<String, String>> out = new LinkedHashMap<>();
		for (String line : contents.split("\n")) {
			if (line.isBlank()) {
				continue;
			}
			JsonNode node = om.readTree(line);
			Map<String, String> vals = new LinkedHashMap<>();
			for (String k : SIDECAR_KEYS) {
				JsonNode v = node.path(k);
				vals.put(k, v.isNull() || v.isMissingNode() ? null : v.asString());
			}
			out.put(node.path("handle").asString(), vals);
		}
		return out;
	}

	/** Vertex 출력엔 key가 없다 — 에코된 request 유저 텍스트 첫 줄에서 복원(콘텐츠의 shortCodeFromEcho 동형). */
	static String handleFromEcho(JsonNode node) {
		JsonNode parts = node.path("request").path("contents").path(0).path("parts");
		for (JsonNode part : parts) {
			String text = part.path("text").asString("");
			java.util.regex.Matcher m = ECHO_HANDLE.matcher(text);
			if (m.find()) {
				return m.group(1);
			}
		}
		return "";
	}
}
