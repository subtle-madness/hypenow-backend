package com.celfit.was.v1.brandmonitoring.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
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
	/** 모델이 생성하지 않은(합성) functionCall에 서명이 없을 때 쓰는 Google 공식 더미 서명
	 * (ai.google.dev/gemini-api/docs/generate-content/thought-signatures - "you can set the
	 * following dummy signatures ... to skip validation", 대안값 "skip_thought_signature_validator"와
	 * 동등하다). {@link #modelToolCallContent} 참조. */
	private static final String DUMMY_THOUGHT_SIGNATURE = "context_engineering_is_the_way_to_go";

	private final ChatTransport transport;
	private final ObjectMapper objectMapper;
	private final Integer thinkingBudget;

	/** 기존 2-인자 생성자 - thinkingBudget=0으로 위임한다(테스트·기존 호출부 호환, 기본값도 0). */
	public GeminiChatClient(ChatTransport transport, ObjectMapper objectMapper) {
		this(transport, objectMapper, 0);
	}

	/**
	 * thinkingBudget이 null이면 thinkingConfig 자체를 요청에 싣지 않는다(모델 기본 동적 thinking에
	 * 맡긴다) - gemini-2.5-pro 계열은 thinking을 끌 수 없어 thinkingBudget=0을 보내면 Vertex가 400
	 * "The model does not support setting thinking_budget to 0"을 돌려준다(2026-09-01 실측,
	 * BRAND_AI_MODEL=gemini-2.5-pro 기동 시 전 호출 재현). flash 계열은 기존대로 0을 넘겨
	 * dynamic thinking이 maxOutputTokens를 잠식하는 것(I7)을 막는다.
	 */
	public GeminiChatClient(ChatTransport transport, ObjectMapper objectMapper, Integer thinkingBudget) {
		this.transport = transport;
		this.objectMapper = objectMapper;
		this.thinkingBudget = thinkingBudget;
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
		ObjectNode body = buildBody(systemPrompt, contents, tools, toolCallsDisabled);
		return parse(transport.post(body.toString()));
	}

	/**
	 * generate()의 스트리밍 버전(T2) - 요청 조립은 완전히 동일하고(경로만 {@link ChatTransport}
	 * 구현체가 다르게 잡는다, T1), 응답만 SSE 청크로 받는다. {@code onChunk}는 SSE data 이벤트가 올
	 * 때마다 그 청크의 델타(텍스트·이번 청크에 새로 나타난 functionCall·이번 청크에 실린 finishReason)를
	 * 받는다 - 홀드백 여부 같은 정책 판단은 호출자({@link BrandAiAgent})의 몫이고, 이 클래스는 순수
	 * 파싱만 한다.
	 *
	 * <p>돌려주는 {@link LlmTurn}은 모든 청크를 누적한 완성 턴이다 - {@link #generate}와 동일한 형태로
	 * 에이전트 루프의 나머지 로직(툴 실행·되먹임·정지 조건 판정)을 그대로 재사용할 수 있게 한다.
	 */
	public LlmTurn generateStream(String systemPrompt, List<JsonNode> contents, List<AiToolSpec> tools,
			boolean toolCallsDisabled, Consumer<Chunk> onChunk) {
		ObjectNode body = buildBody(systemPrompt, contents, tools, toolCallsDisabled);
		StringBuilder text = new StringBuilder();
		List<LlmTurn.ToolCall> allCalls = new ArrayList<>();
		int[] promptTokens = {0};
		int[] outputTokens = {0};
		String[] finishReason = {null};
		boolean[] sawCandidate = {false};

		transport.postStream(body.toString(), raw -> {
			JsonNode root = objectMapper.readTree(raw);
			JsonNode usage = root.path("usageMetadata");
			JsonNode candidates = root.path("candidates");
			JsonNode candidate = candidates.path(0);
			JsonNode parts = candidate.path("content").path("parts");
			StringBuilder deltaText = new StringBuilder();
			List<LlmTurn.ToolCall> deltaCalls = new ArrayList<>();
			for (JsonNode part : parts) {
				JsonNode functionCall = part.path("functionCall");
				if (functionCall.isObject()) {
					JsonNode args = functionCall.path("args");
					// thoughtSignature는 functionCall과 같은 part 레벨의 형제 필드다(Gemini 3.x 공식
					// 문서 "Thought signatures") - 병렬 호출이면 첫 functionCall part에만 실린다.
					LlmTurn.ToolCall call = new LlmTurn.ToolCall(functionCall.path("name").asString(),
							args.isObject() ? args : objectMapper.createObjectNode(),
							part.path("thoughtSignature").asString(null));
					allCalls.add(call);
					deltaCalls.add(call);
				} else if (part.hasNonNull("text")) {
					deltaText.append(part.path("text").asString());
				}
			}
			text.append(deltaText);
			if (!candidates.isEmpty()) {
				sawCandidate[0] = true;
			}
			String chunkFinishReason = null;
			if (candidate.hasNonNull("finishReason")) {
				chunkFinishReason = candidate.path("finishReason").asString();
				finishReason[0] = chunkFinishReason;
			} else if (candidates.isEmpty()) {
				String blockReason = root.path("promptFeedback").path("blockReason").asString(null);
				if (blockReason != null) {
					chunkFinishReason = blockReason;
					finishReason[0] = blockReason;
				}
			}
			if (usage.isObject() && !usage.isMissingNode()) {
				promptTokens[0] = usage.path("promptTokenCount").asInt();
				// I7-④와 동형 - thinking 소비분도 출력 토큰에 합산한다.
				outputTokens[0] = usage.path("candidatesTokenCount").asInt() + usage.path("thoughtsTokenCount").asInt();
			}
			onChunk.accept(new Chunk(deltaText.toString(), List.copyOf(deltaCalls), chunkFinishReason));
		});

		// I7과 동형 - 청크 전체에서 후보를 한 번도 못 봤고 finishReason도 못 얻었으면(전부 프롬프트
		// 차단 등) NO_CANDIDATES로 대체한다.
		String resolvedFinishReason = finishReason[0] != null ? finishReason[0] : sawCandidate[0] ? null : "NO_CANDIDATES";
		return new LlmTurn(text.toString(), List.copyOf(allCalls), promptTokens[0], outputTokens[0],
				resolvedFinishReason);
	}

	/** generate()·generateStream() 공통 요청 본문 조립(T2 리팩터) - I7 thinkingBudget 기본값 0,
	 * I8 강제 답변 턴에서도 tools 유지 등 기존 규칙을 그대로 지킨다. */
	private ObjectNode buildBody(String systemPrompt, List<JsonNode> contents, List<AiToolSpec> tools,
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
		applyThinkingConfig(generation);
		return body;
	}

	/**
	 * thinkingBudget=0(I7) - gemini-2.5-flash는 dynamic thinking이 기본이라 미지정 시 thinking
	 * 토큰이 maxOutputTokens를 잠식해 finishReason=MAX_TOKENS로 parts 없이 끝날 수 있다.
	 * thinkingBudget이 null이면(pro 계열, 2026-09-01 실측) thinkingConfig 자체를 생략해 모델
	 * 기본 동적 thinking에 맡긴다 - 0을 보내면 400이 난다.
	 */
	private void applyThinkingConfig(ObjectNode generation) {
		if (thinkingBudget != null) {
			generation.putObject("thinkingConfig").put("thinkingBudget", thinkingBudget);
		}
	}

	/**
	 * 구조화 출력 1회 호출(설계 밖 보조 호출, FE 변경요청서 §3.3 followUps 생성 전용) - 도구·대화이력
	 * 없이 시스템+유저 텍스트 한 쌍만 보내고 {@code responseSchema}로 출력 형태를 강제한다
	 * ({@code monitoring.llm.BrandMentionJudge}와 같은 관용구). generate()와 달리 tools를 전혀 싣지
	 * 않는다 - 이 호출은 답변 텍스트를 재료로 다음 질문을 뽑아내는 것뿐이라 툴 콜링이 필요 없다.
	 */
	public String generateStructured(String systemPrompt, String userText, JsonNode responseSchema,
			int maxOutputTokens) {
		ObjectNode body = objectMapper.createObjectNode();
		body.putObject("systemInstruction").putArray("parts").addObject().put("text", systemPrompt);
		ObjectNode content = body.putArray("contents").addObject();
		content.put("role", "user");
		content.putArray("parts").addObject().put("text", userText);
		ObjectNode generation = body.putObject("generationConfig");
		generation.put("temperature", 0.4);
		generation.put("maxOutputTokens", maxOutputTokens);
		generation.put("responseMimeType", "application/json");
		generation.set("responseSchema", responseSchema);
		applyThinkingConfig(generation);
		return transport.post(body.toString());
	}

	public JsonNode userContent(String text) {
		return textContent("user", text);
	}

	public JsonNode modelContent(String text) {
		return textContent("model", text);
	}

	/**
	 * 모델이 요청한 툴 호출을 대화 이력에 그대로 되돌려 넣는다 - 없으면 다음 턴에서 문맥이 끊긴다.
	 *
	 * <p>Gemini 3.x는 thoughtSignature를 엄격 검증한다(공식 문서 "Thought signatures") - "각 턴의
	 * 첫 functionCall part는 반드시 thoughtSignature를 실어야 한다", 없으면 400
	 * "Function call ... is missing a thought_signature"가 난다. 실제 모델 응답을 파싱한 호출은
	 * {@link LlmTurn.ToolCall#thoughtSignature()}에 캡처된 값을 그대로 되돌려 보낸다(병렬 호출이면
	 * 첫 파트만 서명을 갖고 있는 게 정상이라 나머지는 없어도 된다 - 같은 문서). 서명이 없는데 첫
	 * 파트인 경우는 우리가 직접 합성한 호출뿐이다({@link BrandAiAgent#injectPlannedCalls} 프리셋
	 * 선실행) - 이때는 문서가 안내하는 더미 서명({@value #DUMMY_THOUGHT_SIGNATURE})으로 채워 검증을
	 * 스킵시킨다.
	 */
	public JsonNode modelToolCallContent(List<LlmTurn.ToolCall> calls) {
		ObjectNode content = objectMapper.createObjectNode();
		content.put("role", "model");
		ArrayNode parts = content.putArray("parts");
		for (int i = 0; i < calls.size(); i++) {
			LlmTurn.ToolCall call = calls.get(i);
			ObjectNode part = parts.addObject();
			ObjectNode functionCall = part.putObject("functionCall");
			functionCall.put("name", call.name());
			functionCall.set("args", call.args());
			if (call.thoughtSignature() != null) {
				part.put("thoughtSignature", call.thoughtSignature());
			} else if (i == 0) {
				part.put("thoughtSignature", DUMMY_THOUGHT_SIGNATURE);
			}
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
						args.isObject() ? args : objectMapper.createObjectNode(),
						part.path("thoughtSignature").asString(null)));
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

	/**
	 * SSE 청크 1건(T2, {@link #generateStream}) - Vertex streamGenerateContent가 보낸 data 이벤트
	 * 1건의 파싱 결과. textDelta는 <b>이번 청크만의</b> 텍스트(누적 아님) - Gemini 스트리밍은 각
	 * data 이벤트에 그 시점의 증분 텍스트만 싣는다. toolCalls도 이번 청크에서 새로 나타난 것만이다
	 * (보통 스트림 끝 무렵 한 청크에 몰려 온다). finishReason은 이번 청크에 실제로 실려온 경우만(대개
	 * 마지막 청크) - 없으면 null.
	 */
	public record Chunk(String textDelta, List<LlmTurn.ToolCall> toolCalls, String finishReason) {
	}
}
