package com.celfit.was.v1.brandmonitoring.ai;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;
import tools.jackson.databind.JsonNode;

/**
 * 대화 상세의 메시지 1건(FE 변경요청서 2026-08-28 §8) - app.ai_chat_logs 1행이 user·assistant 메시지
 * 최대 2건으로 펼쳐진다(답변이 없는 행은 user 메시지 1건뿐).
 *
 * <p>presetId·followUps·references는 null이면 응답에서 아예 빠진다(NON_NULL) - presetId는 사용자
 * 메시지에만, followUps·references는 <b>대화 전체에서 마지막 assistant 메시지에만</b> 싣는다
 * (컨트롤러 조립 규칙, §8). feedback은 아직 기능이 없어 항상 null이고 그대로 노출한다(NON_NULL을
 * 걸지 않는다 - 프론트가 "이 필드가 존재하는지"가 아니라 값 자체로 상태를 구분한다).
 */
public record AiConversationMessage(String role, String content,
		@JsonInclude(JsonInclude.Include.NON_NULL) String presetId, Object feedback, OffsetDateTime createdAt,
		@JsonInclude(JsonInclude.Include.NON_NULL) JsonNode followUps,
		@JsonInclude(JsonInclude.Include.NON_NULL) JsonNode references) {

	public static final String ROLE_USER = "user";
	public static final String ROLE_ASSISTANT = "assistant";

	/** 후속질문·참조 없는 메시지(가장 흔한 경우) - 마지막 assistant 메시지만 별도 팩토리로 붙인다. */
	public static AiConversationMessage of(String role, String content, String presetId, OffsetDateTime createdAt) {
		return new AiConversationMessage(role, content, presetId, null, createdAt, null, null);
	}

	/** 후속질문·참조를 실어 재구성한다 - 대화 전체의 마지막 assistant 메시지에만 호출된다. */
	public AiConversationMessage withFollowUpsAndReferences(JsonNode followUps, JsonNode references) {
		return new AiConversationMessage(role, content, presetId, feedback, createdAt, followUps, references);
	}
}
