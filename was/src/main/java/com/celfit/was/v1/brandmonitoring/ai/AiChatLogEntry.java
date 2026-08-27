package com.celfit.was.v1.brandmonitoring.ai;

import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * app.ai_chat_logs 1행(설계 §6) - 질문 1건당 1행. 에이전트 루프가 끝난 뒤 컨트롤러가 조립한다.
 *
 * @param brandId 모델이 툴 인자로 실제 조회한 브랜드. 특정되지 않았으면 null.
 * @param answer  LLM 실패 시 null - 실패한 질문도 수요 신호라 행은 남긴다.
 */
public record AiChatLogEntry(long userId, Long brandId, String question, String answer,
		List<ToolCallLog> toolCalls, int promptTokens, int outputTokens, long elapsedMillis,
		String outcome) {

	/** 정상 답변 완료. */
	public static final String OUTCOME_OK = "ok";
	/** 툴 호출 상한(8회)에 걸려 그때까지의 정보로 답변을 강제한 경우. */
	public static final String OUTCOME_TOOL_CAP = "tool_cap";
	/** LLM 전송 실패(타임아웃·쿼터·5xx) - answer는 null. */
	public static final String OUTCOME_LLM_FAILED = "llm_failed";

	/**
	 * 툴 호출 1건 기록 - args는 모델이 넘긴 원본 인자 노드, rows는 툴이 돌려준 행 수.
	 * JsonNode를 그대로 담아 Jackson이 jsonb 문자열로 직렬화하게 한다(문자열로 들고 있으면
	 * 이중 인코딩된다).
	 */
	public record ToolCallLog(String name, JsonNode args, int rows) {
	}
}
