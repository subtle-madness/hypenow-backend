package com.celfit.was.v1.brandmonitoring.ai;

import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * app.ai_chat_logs 1행(설계 §6) - 질문 1건당 1행. 에이전트 루프가 끝난 뒤 컨트롤러가 조립한다.
 *
 * @param brandId        모델이 툴 인자로 실제 조회한 브랜드. 특정되지 않았으면 null.
 * @param answer         LLM 실패 시 null - 실패한 질문도 수요 신호라 행은 남긴다.
 * @param conversationId 이 질문이 속한 대화(app.ai_conversations). 대화에 안 묶인 질문은 null
 *                        (FE 변경요청서 2026-08-28 §8).
 * @param presetId       질문을 촉발한 프리셋 식별자. 자유 질의는 null.
 * @param scope          질문 당시 프론트가 지정한 조회 범위(기간·계정 등). 없으면 null.
 * @param followUps      답변에 딸린 후속 질문 제안 목록(jsonb 배열). 없으면 빈 배열.
 * @param refs           답변이 인용한 참조 목록(jsonb 배열, 컬럼명은 SQL 예약어 회피로 refs).
 *                        없으면 빈 배열.
 */
public record AiChatLogEntry(long userId, Long brandId, String question, String answer,
		List<ToolCallLog> toolCalls, int promptTokens, int outputTokens, long elapsedMillis,
		String outcome, Long conversationId, String presetId, JsonNode scope, JsonNode followUps,
		JsonNode refs) {

	/** 대화·프리셋·범위·후속질문·참조 필드 없이 적재하던 기존 호출부 호환용(설계 §8 도입 이전
	 * 관용구) - 신규 필드는 전부 빈 값(null 또는 빈 jsonb 배열)으로 채운다. */
	public AiChatLogEntry(long userId, Long brandId, String question, String answer,
			List<ToolCallLog> toolCalls, int promptTokens, int outputTokens, long elapsedMillis,
			String outcome) {
		this(userId, brandId, question, answer, toolCalls, promptTokens, outputTokens, elapsedMillis,
				outcome, null, null, null, JsonNodeFactory.instance.arrayNode(),
				JsonNodeFactory.instance.arrayNode());
	}

	/** 정상 답변 완료. */
	public static final String OUTCOME_OK = "ok";
	/** 답변 강제 상한(툴 호출 8회 · 벽시계 예산 55초 · 누적 프롬프트 토큰 예산 중 하나)에 걸려
	 * 그때까지의 정보로 답변을 강제한 경우 - 원인은 로그의 toolCalls·elapsedMillis·promptTokens로 구분한다. */
	public static final String OUTCOME_TOOL_CAP = "tool_cap";
	/** LLM 호출 안전망(BrandAiAgent#MAX_LLM_CALLS, 12회)까지 도달한 병리적 경우(M2) - OUTCOME_TOOL_CAP과
	 * 구분해 도달 시 반드시 남기는 warn 로그와 연결해 추적한다. 정상적으로는 도달하면 안 된다. */
	public static final String OUTCOME_LLM_CALL_CAP = "llm_call_cap";
	/** LLM 전송 실패(타임아웃·쿼터·5xx) - answer는 null. */
	public static final String OUTCOME_LLM_FAILED = "llm_failed";
	/** 안전 필터 차단 또는 thinking이 maxOutputTokens를 잠식한 MAX_TOKENS로 candidates가 비거나
	 * 텍스트 없이 끝난 경우(I7) - 사용자에게는 정중한 안내 답변을 주고 OUTCOME_OK로 오분류하지 않는다. */
	public static final String OUTCOME_BLOCKED = "blocked";

	/**
	 * 툴 호출 1건 기록 - args는 모델이 넘긴 원본 인자 노드, rows는 툴이 돌려준 행 수.
	 * JsonNode를 그대로 담아 Jackson이 jsonb 문자열로 직렬화하게 한다(문자열로 들고 있으면
	 * 이중 인코딩된다).
	 */
	public record ToolCallLog(String name, JsonNode args, int rows) {
	}
}
