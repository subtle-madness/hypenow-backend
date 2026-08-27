package com.celfit.was.v1.brandmonitoring.ai;

import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * LLM 1턴의 응답 - 텍스트와 툴 호출은 배타적이지 않다(Gemini가 둘을 같은 파트 배열에 섞어 보낼 수
 * 있다). 에이전트 루프는 toolCalls가 비었을 때만 종료한다.
 *
 * @param finishReason Vertex candidates[0].finishReason(정상 종료는 "STOP") 또는 후보 자체가 없을 때
 *                      promptFeedback.blockReason("NO_CANDIDATES"로 대체)(I7). 안전 필터 차단·
 *                      thinking이 maxOutputTokens를 잠식한 MAX_TOKENS 케이스를 에이전트가 구분해
 *                      OUTCOME_BLOCKED로 기록하는 데 쓴다.
 */
public record LlmTurn(String text, List<ToolCall> toolCalls, int promptTokens, int outputTokens,
		String finishReason) {

	/** 모델이 요청한 툴 호출 1건. args는 항상 object 노드(빈 object 포함). */
	public record ToolCall(String name, JsonNode args) {
	}
}
