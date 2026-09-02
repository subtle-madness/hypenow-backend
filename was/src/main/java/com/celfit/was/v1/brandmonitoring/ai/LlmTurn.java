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

	/**
	 * 모델이 요청한 툴 호출 1건. args는 항상 object 노드(빈 object 포함).
	 *
	 * @param thoughtSignature Gemini 3.x가 functionCall part에 실어 보내는 암호화된 추론 상태
	 *                          서명(공식 문서 "Thought signatures" - 대화 이력을 되돌려 보낼 때 그대로
	 *                          echo해야 한다, 없으면 다음 턴에서 400 "missing a thought_signature").
	 *                          모델 응답을 파싱한 호출은 실제 값(병렬 호출이면 첫 파트만)을, 프리셋
	 *                          선실행처럼 우리가 직접 합성한 호출은 null을 담는다 -
	 *                          {@link GeminiChatClient#modelToolCallContent}가 되돌려 보낼 때
	 *                          null이면 문서가 안내하는 더미 서명으로 채운다.
	 */
	public record ToolCall(String name, JsonNode args, String thoughtSignature) {
	}
}
