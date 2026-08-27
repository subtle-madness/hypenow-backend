package com.celfit.was.v1.brandmonitoring.ai;

import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * LLM 1턴의 응답 - 텍스트와 툴 호출은 배타적이지 않다(Gemini가 둘을 같은 파트 배열에 섞어 보낼 수
 * 있다). 에이전트 루프는 toolCalls가 비었을 때만 종료한다.
 */
public record LlmTurn(String text, List<ToolCall> toolCalls, int promptTokens, int outputTokens) {

	/** 모델이 요청한 툴 호출 1건. args는 항상 object 노드(빈 object 포함). */
	public record ToolCall(String name, JsonNode args) {
	}
}
