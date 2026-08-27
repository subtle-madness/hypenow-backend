package com.celfit.was.v1.brandmonitoring.ai;

import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Vertex Gemini generateContent 요청 조립·응답 파싱(설계 §3). 프롬프트 내용·툴 목록은 호출자
 * ({@link BrandAiAgent})가 정하고, 이 클래스는 "Gemini가 알아듣는 JSON 형태"만 안다.
 *
 * <p>contents 조립 헬퍼를 여기 둔 이유: 모델 턴(functionCall)과 툴 결과 턴(functionResponse)의
 * role 규약이 Vertex 계약의 일부라 루프 쪽에 흩어지면 안 된다. functionResponse는 role="user"로
 * 보낸다(Vertex REST 계약).
 */
public class GeminiChatClient {

	private static final double TEMPERATURE = 0.2;
	private static final int MAX_OUTPUT_TOKENS = 2048;

	private final ChatTransport transport;
	private final ObjectMapper objectMapper;

	public GeminiChatClient(ChatTransport transport, ObjectMapper objectMapper) {
		this.transport = transport;
		this.objectMapper = objectMapper;
	}

	/** tools가 비면 tools 필드를 싣지 않는다 - 툴 정의 자체가 없는 호출용(현재 실사용처 없음, 방어적으로 유지). */
	public LlmTurn generate(String systemPrompt, List<JsonNode> contents, List<AiToolSpec> tools) {
		return generate(systemPrompt, contents, tools, false);
	}

	/**
	 * toolCallsDisabled=true면 tools 선언은 그대로 싣되
	 * {@code toolConfig.functionCallingConfig.mode="NONE"}으로 호출만 막는다(I8, 설계 §7 툴 상한
	 * 강제 답변 턴 용도). tools를 통째로 빼면 이전 턴의 functionCall/functionResponse 파트가 남은
	 * 히스토리와 조합돼 Vertex가 400을 돌려줄 위험이 있다 - Vertex 공식 권장 표현이 tools 유지 +
	 * mode NONE이다.
	 */
	public LlmTurn generate(String systemPrompt, List<JsonNode> contents, List<AiToolSpec> tools,
			boolean toolCallsDisabled) {
		ObjectNode body = objectMapper.createObjectNode();
		body.putObject("systemInstruction").putArray("parts").addObject().put("text", systemPrompt);
		ArrayNode contentsNode = body.putArray("contents");
		contents.forEach(contentsNode::add);
		if (!tools.isEmpty()) {
			ArrayNode declarations = body.putArray("tools").addObject().putArray("functionDeclarations");
			for (AiToolSpec spec : tools) {
				ObjectNode declaration = declarations.addObject();
				declaration.put("name", spec.name());
				declaration.put("description", spec.description());
				if (spec.parametersJson() != null) {
					declaration.set("parameters", objectMapper.readTree(spec.parametersJson()));
				}
			}
			if (toolCallsDisabled) {
				body.putObject("toolConfig").putObject("functionCallingConfig").put("mode", "NONE");
			}
		}
		ObjectNode generation = body.putObject("generationConfig");
		generation.put("temperature", TEMPERATURE);
		generation.put("maxOutputTokens", MAX_OUTPUT_TOKENS);
		// thinkingBudget=0(I7) - gemini-2.5는 dynamic thinking이 기본이라 미지정 시 thinking 토큰이
		// maxOutputTokens를 잠식해 finishReason=MAX_TOKENS로 parts 없이 끝날 수 있다.
		generation.putObject("thinkingConfig").put("thinkingBudget", 0);
		return parse(transport.post(body.toString()));
	}

	public JsonNode userContent(String text) {
		return textContent("user", text);
	}

	public JsonNode modelContent(String text) {
		return textContent("model", text);
	}

	/** 모델이 요청한 툴 호출을 대화 이력에 그대로 되돌려 넣는다 - 없으면 다음 턴에서 문맥이 끊긴다. */
	public JsonNode modelToolCallContent(List<LlmTurn.ToolCall> calls) {
		ObjectNode content = objectMapper.createObjectNode();
		content.put("role", "model");
		ArrayNode parts = content.putArray("parts");
		for (LlmTurn.ToolCall call : calls) {
			ObjectNode functionCall = parts.addObject().putObject("functionCall");
			functionCall.put("name", call.name());
			functionCall.set("args", call.args());
		}
		return content;
	}

	/** 툴 실행 결과 되먹임. payloadJson은 임의 JSON이며 {"result": ...}로 감싸 보낸다(Vertex 계약). */
	public JsonNode toolResultContent(List<ToolResponse> responses) {
		ObjectNode content = objectMapper.createObjectNode();
		content.put("role", "user");
		ArrayNode parts = content.putArray("parts");
		for (ToolResponse response : responses) {
			ObjectNode functionResponse = parts.addObject().putObject("functionResponse");
			functionResponse.put("name", response.name());
			functionResponse.putObject("response").set("result", objectMapper.readTree(response.payloadJson()));
		}
		return content;
	}

	private JsonNode textContent(String role, String text) {
		ObjectNode content = objectMapper.createObjectNode();
		content.put("role", role);
		content.putArray("parts").addObject().put("text", text);
		return content;
	}

	/**
	 * finishReason은 candidates[0]에서 읽되(I7), 후보 자체가 없으면(안전 필터가 프롬프트를 통째로
	 * 막은 경우) promptFeedback.blockReason으로 대신한다 - 둘 다 "정상 STOP이 아닌 이유"를
	 * BrandAiAgent가 OUTCOME_BLOCKED 판정에 쓸 수 있게 하나의 문자열로 합쳐 돌려준다.
	 */
	private LlmTurn parse(String raw) {
		JsonNode root = objectMapper.readTree(raw);
		JsonNode usage = root.path("usageMetadata");
		JsonNode candidates = root.path("candidates");
		JsonNode candidate = candidates.path(0);
		JsonNode parts = candidate.path("content").path("parts");
		StringBuilder text = new StringBuilder();
		List<LlmTurn.ToolCall> calls = new ArrayList<>();
		for (JsonNode part : parts) {
			JsonNode functionCall = part.path("functionCall");
			if (functionCall.isObject()) {
				JsonNode args = functionCall.path("args");
				calls.add(new LlmTurn.ToolCall(functionCall.path("name").asString(),
						args.isObject() ? args : objectMapper.createObjectNode()));
			} else if (part.hasNonNull("text")) {
				text.append(part.path("text").asString());
			}
		}
		String finishReason = candidate.hasNonNull("finishReason") ? candidate.path("finishReason").asString() : null;
		if (candidates.isEmpty()) {
			String blockReason = root.path("promptFeedback").path("blockReason").asString(null);
			finishReason = blockReason != null ? blockReason : "NO_CANDIDATES";
		}
		// thoughtsTokenCount(I7-④) - dynamic thinking 소비분도 출력 토큰 예산에 합산해야 실사용량이 맞다.
		int outputTokens = usage.path("candidatesTokenCount").asInt() + usage.path("thoughtsTokenCount").asInt();
		return new LlmTurn(text.toString(), List.copyOf(calls),
				usage.path("promptTokenCount").asInt(), outputTokens, finishReason);
	}

	/** 툴 결과 1건 - payloadJson은 {@link AiToolResult#payloadJson()} 그대로다. */
	public record ToolResponse(String name, String payloadJson) {
	}
}
