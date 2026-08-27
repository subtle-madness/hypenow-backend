package com.celfit.was.v1.brandmonitoring.ai;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 에이전트 루프(설계 §3) - 시스템 프롬프트 + 대화 이력을 LLM에 보내고, 툴 호출이 오면 실행해
 * 되먹이기를 반복하다 텍스트 답변이 나오면 끝낸다. SSE·서버 세션은 스코프 밖이다(설계 §10).
 *
 * <p>정지 조건이 두 겹이다: 툴 호출 {@value #MAX_TOOL_CALLS}회(설계 §7)에 걸리면 다음 턴을 툴 없이
 * 보내 답변을 강제하고, 그래도 안 끝나는 병리적 경우를 위해 LLM 호출 자체를 {@value #MAX_LLM_CALLS}회로
 * 막는다. 후자는 도달하면 안 되는 안전망이라 도달 시 warn을 남긴다.
 *
 * <p>툴 실패는 같은 툴 기준 1회만 재시도 지시를 붙여 되먹인다(설계 §8) - 두 번째부터는
 * {@code retry: false}로 "이 정보 없이 답하라"고 못 박는다. 그러지 않으면 모델이 같은 실패를
 * 상한까지 반복한다.
 */
public class BrandAiAgent {

	/** 턴당 툴 호출 상한(설계 §7). */
	static final int MAX_TOOL_CALLS = 8;
	/** LLM 호출 안전망 - 툴 상한 도달 후 강제 답변 턴까지 감안한 여유값. */
	static final int MAX_LLM_CALLS = 12;
	/** 대화 이력에서 되살리지 못한 답변을 대신할 문구. */
	private static final String FALLBACK_ANSWER =
			"확인한 내용을 정리하지 못했어요. 질문을 조금 더 좁혀서 다시 물어봐 주세요.";

	private static final Logger log = LoggerFactory.getLogger(BrandAiAgent.class);

	private final GeminiChatClient client;
	private final BrandAiToolbox toolbox;
	private final ObjectMapper objectMapper;

	public BrandAiAgent(GeminiChatClient client, BrandAiToolbox toolbox, ObjectMapper objectMapper) {
		this.client = client;
		this.toolbox = toolbox;
		this.objectMapper = objectMapper;
	}

	public AgentOutcome run(long userId, List<AiChatMessage> messages) {
		List<JsonNode> contents = new ArrayList<>();
		for (AiChatMessage message : messages) {
			contents.add(AiChatMessage.ROLE_ASSISTANT.equals(message.role())
					? client.modelContent(message.content())
					: client.userContent(message.content()));
		}

		List<AiChatLogEntry.ToolCallLog> toolCalls = new ArrayList<>();
		LinkedHashSet<String> shortCodes = new LinkedHashSet<>();
		Map<String, Integer> failuresByTool = new HashMap<>();
		Long brandId = null;
		int promptTokens = 0;
		int outputTokens = 0;

		for (int llmCall = 1; llmCall <= MAX_LLM_CALLS; llmCall++) {
			boolean capped = toolCalls.size() >= MAX_TOOL_CALLS;
			LlmTurn turn = client.generate(
					capped ? BrandAiPrompt.SYSTEM + BrandAiPrompt.TOOL_CAP_NOTE : BrandAiPrompt.SYSTEM,
					contents,
					capped ? List.of() : BrandAiToolSpecs.ALL);
			promptTokens += turn.promptTokens();
			outputTokens += turn.outputTokens();

			if (turn.toolCalls().isEmpty()) {
				String answer = turn.text().isBlank() ? FALLBACK_ANSWER : turn.text();
				return new AgentOutcome(answer, List.copyOf(shortCodes), List.copyOf(toolCalls),
						promptTokens, outputTokens, brandId,
						capped ? AiChatLogEntry.OUTCOME_TOOL_CAP : AiChatLogEntry.OUTCOME_OK);
			}

			contents.add(client.modelToolCallContent(turn.toolCalls()));
			List<GeminiChatClient.ToolResponse> responses = new ArrayList<>();
			for (LlmTurn.ToolCall call : turn.toolCalls()) {
				if (toolCalls.size() >= MAX_TOOL_CALLS) {
					responses.add(new GeminiChatClient.ToolResponse(call.name(),
							objectMapper.createObjectNode().put("error", "조회 가능 횟수를 모두 썼습니다.")
									.put("retry", false).toString()));
					continue;
				}
				AiToolResult result = toolbox.execute(userId, call.name(), call.args());
				toolCalls.add(new AiChatLogEntry.ToolCallLog(call.name(), call.args(), result.rowCount()));
				shortCodes.addAll(result.shortCodes());
				if (brandId == null && call.args().hasNonNull("brandId")) {
					brandId = call.args().path("brandId").asLong();
				}
				responses.add(new GeminiChatClient.ToolResponse(call.name(),
						result.failed() ? withRetryHint(call.name(), result, failuresByTool)
								: result.payloadJson()));
			}
			contents.add(client.toolResultContent(responses));
		}

		log.warn("AI 에이전트 LLM 호출 안전망 도달 - userId={}, 툴 호출 {}회", userId, toolCalls.size());
		return new AgentOutcome(FALLBACK_ANSWER, List.copyOf(shortCodes), List.copyOf(toolCalls),
				promptTokens, outputTokens, brandId, AiChatLogEntry.OUTCOME_TOOL_CAP);
	}

	/** 같은 툴의 첫 실패에만 retry=true를 붙인다 - 두 번째부터는 물러나라고 지시한다(설계 §8). */
	private String withRetryHint(String toolName, AiToolResult result, Map<String, Integer> failuresByTool) {
		int failures = failuresByTool.merge(toolName, 1, Integer::sum);
		ObjectNode payload = (ObjectNode) objectMapper.readTree(result.payloadJson());
		payload.put("retry", failures == 1);
		if (failures > 1) {
			payload.put("hint", "이 정보 없이 지금까지 확인한 내용으로 답하세요.");
		}
		return payload.toString();
	}

	/**
	 * 루프 1회의 산출물.
	 *
	 * @param brandId 모델이 처음 넘긴 brandId 인자 - 로그 분석에서 "어느 브랜드 질문인가"를 가른다.
	 */
	public record AgentOutcome(String answer, List<String> referencedShortCodes,
			List<AiChatLogEntry.ToolCallLog> toolCalls, int promptTokens, int outputTokens,
			Long brandId, String outcome) {
	}
}
