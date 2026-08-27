package com.celfit.was.v1.brandmonitoring.ai;

import java.time.Clock;
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
 * <p>정지 조건이 네 겹이다(C2/I6) - 툴 호출 {@value #MAX_TOOL_CALLS}회(설계 §7), 누적 프롬프트
 * 토큰 {@value #PROMPT_TOKEN_BUDGET}(댓글 본문 무절단×매 턴 전체 재전송의 O(k²) 폭발 방지, I6),
 * 벽시계 예산 {@value #TIME_BUDGET_MILLIS}ms(1 LLM 호출 최악 92초까지 걸릴 수 있어 안전망 12회를
 * 다 채우면 수십 분이 걸리는 것을 막는다) 중 하나라도 걸리면 다음 턴을 툴 호출 불가로 보내 답변을
 * 강제하고, 그래도 안 끝나는 병리적 경우를 위해 LLM 호출 자체를 {@value #MAX_LLM_CALLS}회로 막는다
 * (M2 - 이 경로는 OUTCOME_LLM_CALL_CAP으로 앞의 강제 답변 상한과 구분해 기록하고, 도달하면 안 되는
 * 안전망이라 도달 시 warn을 남긴다).
 *
 * <p>강제 답변 턴에서도 tools 선언 자체는 유지하고 {@code toolConfig} mode만 NONE으로 막는다(I8) -
 * tools를 통째로 빼면 이전 턴의 functionCall/functionResponse 파트가 남은 히스토리와 조합돼 Vertex
 * 400 위험이 있다.
 *
 * <p>안전 필터 차단·thinking이 maxOutputTokens를 잠식한 MAX_TOKENS로 후보가 비거나 텍스트 없이
 * 끝나면(I7) OUTCOME_BLOCKED로 기록하고 정중한 안내 답변을 돌려준다 - FALLBACK_ANSWER를
 * OUTCOME_OK로 오분류하지 않는다.
 *
 * <p>툴 실패는 같은 툴 기준 1회만 재시도 지시를 붙여 되먹인다(설계 §8) - 두 번째부터는
 * {@code retry: false}로 "이 정보 없이 답하라"고 못 박는다. 그러지 않으면 모델이 같은 실패를
 * 상한까지 반복한다.
 */
public class BrandAiAgent {

	/** 턴당 툴 호출 상한(설계 §7). */
	static final int MAX_TOOL_CALLS = 8;
	/** LLM 호출 안전망 - 강제 답변 턴까지 감안한 여유값. */
	static final int MAX_LLM_CALLS = 12;
	/** 누적 프롬프트 토큰 예산(I6) - 댓글 본문 무절단 × 매 턴 전체 재전송이면 O(k²)로 토큰이
	 * 터진다. 절단(BrandAiToolbox)과 별개로 루프 차원의 두 번째 방어선이다. */
	static final int PROMPT_TOKEN_BUDGET = 60_000;
	/** 벽시계 예산(C2, 55초) - 컨트롤러의 60초 응답 계약(설계 §5) 안에 여유를 두고 강제 답변으로
	 * 전환한다. Vertex 요청 타임아웃을 45초로 줄여도(BrandAiConfig) 재시도 1회를 더하면 최악
	 * 92초까지 걸릴 수 있어, 이 예산이 소진되면 그 한 번의 호출을 끝으로 더 부르지 않는다. */
	static final long TIME_BUDGET_MILLIS = 55_000L;
	/** 정상 종료를 뜻하는 finishReason - 이 값이 아니면서 텍스트·툴 호출이 모두 없으면 막힌 것으로
	 * 본다(I7, SAFETY·MAX_TOKENS·후보 부재 전부 포함 - Vertex가 추가할 수 있는 다른 비정상 사유도
	 * 같은 취급이 맞다). */
	private static final String FINISH_REASON_STOP = "STOP";
	/** 대화 이력에서 되살리지 못한 답변을 대신할 문구. */
	private static final String FALLBACK_ANSWER =
			"확인한 내용을 정리하지 못했어요. 질문을 조금 더 좁혀서 다시 물어봐 주세요.";
	/** 안전 필터·응답 길이 제한으로 막혀 답변 자체를 만들지 못했을 때 돌려줄 안내 문구(I7). */
	private static final String BLOCKED_ANSWER =
			"이 질문에는 안전 정책이나 응답 길이 제한 때문에 답변을 만들지 못했어요. 질문을 조금 다르게 바꿔서 다시 시도해 주세요.";

	private static final Logger log = LoggerFactory.getLogger(BrandAiAgent.class);

	private final GeminiChatClient client;
	private final BrandAiToolbox toolbox;
	private final ObjectMapper objectMapper;
	private final Clock clock;

	public BrandAiAgent(GeminiChatClient client, BrandAiToolbox toolbox, ObjectMapper objectMapper, Clock clock) {
		this.client = client;
		this.toolbox = toolbox;
		this.objectMapper = objectMapper;
		this.clock = clock;
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
		long deadline = clock.millis() + TIME_BUDGET_MILLIS;

		for (int llmCall = 1; llmCall <= MAX_LLM_CALLS; llmCall++) {
			// 매 호출 전에 남은 예산을 확인한다(C2) - 부족하면 이번 호출을 마지막으로 삼아 답변을 강제한다.
			boolean capped = toolCalls.size() >= MAX_TOOL_CALLS
					|| promptTokens >= PROMPT_TOKEN_BUDGET
					|| clock.millis() >= deadline;
			String systemPrompt = capped
					? BrandAiPrompt.SYSTEM + BrandAiPrompt.TIME_BUDGET_NOTE
					: BrandAiPrompt.SYSTEM;
			LlmTurn turn = client.generate(systemPrompt, contents, BrandAiToolSpecs.ALL, capped);
			promptTokens += turn.promptTokens();
			outputTokens += turn.outputTokens();

			if (turn.toolCalls().isEmpty()) {
				if (turn.text().isBlank() && isBlocked(turn.finishReason())) {
					return new AgentOutcome(BLOCKED_ANSWER, List.copyOf(shortCodes), List.copyOf(toolCalls),
							promptTokens, outputTokens, brandId, AiChatLogEntry.OUTCOME_BLOCKED);
				}
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
				// 소유 검증 실패 등 failed 결과의 brandId는 신뢰할 수 없다(M1) - 성공한 호출에서만 딴다.
				if (brandId == null && !result.failed() && call.args().hasNonNull("brandId")) {
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
				promptTokens, outputTokens, brandId, AiChatLogEntry.OUTCOME_LLM_CALL_CAP);
	}

	/** STOP이 아니면서 텍스트도 툴 호출도 없는 경우만 "막혔다"로 본다(I7) - finishReason 자체가
	 * 없는(구버전 응답 등) 경우는 정상 완료로 보고 기존 FALLBACK_ANSWER 경로를 그대로 탄다. */
	private static boolean isBlocked(String finishReason) {
		return finishReason != null && !FINISH_REASON_STOP.equals(finishReason);
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
