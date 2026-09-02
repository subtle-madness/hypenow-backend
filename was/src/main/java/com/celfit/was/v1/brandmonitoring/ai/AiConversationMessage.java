package com.celfit.was.v1.brandmonitoring.ai;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;
import tools.jackson.databind.JsonNode;

/**
 * 대화 상세의 메시지 1건(FE 변경요청서 2026-08-28 §8) - app.ai_chat_logs 1행이 user·assistant 메시지
 * 최대 2건으로 펼쳐진다(답변이 없는 행은 user 메시지 1건뿐).
 *
 * <p>presetId·messageId·followUps·references는 null이면 응답에서 아예 빠진다(NON_NULL) - presetId는
 * 사용자 메시지에만, messageId·followUps·references는 <b>assistant 메시지에만</b> 싣는다(followUps·
 * references는 그중에서도 대화 전체의 마지막 assistant 메시지에만, 컨트롤러 조립 규칙, §8). feedback은
 * NON_NULL을 걸지 않고 항상 노출한다 - 프론트가 "이 필드가 존재하는지"가 아니라 값 자체(피드백 없음
 * =null)로 상태를 구분한다.
 *
 * <p>messageId는 이 메시지를 적재한 app.ai_chat_logs 행 id(문자열) - {@code POST .../messages}
 * 응답의 messageId·SSE done 이벤트와 동일한 값이라, 대화를 다시 열었을 때도 프론트가 같은 값으로
 * 피드백 API({@code PUT/DELETE .../messages/{messageId}/feedback})를 호출할 수 있다(2026-09-02
 * 피드백 저장 API 추가 시 도입 - feedback 타입도 이때 Object → {@link Feedback}로 명시했다).
 */
public record AiConversationMessage(String role, String content,
		@JsonInclude(JsonInclude.Include.NON_NULL) String presetId,
		@JsonInclude(JsonInclude.Include.NON_NULL) String messageId, Feedback feedback, OffsetDateTime createdAt,
		@JsonInclude(JsonInclude.Include.NON_NULL) JsonNode followUps,
		@JsonInclude(JsonInclude.Include.NON_NULL) JsonNode references) {

	public static final String ROLE_USER = "user";
	public static final String ROLE_ASSISTANT = "assistant";

	/** 후속질문·참조·messageId·피드백 없는 메시지(user 메시지, 또는 조립 전 임시 상태) - 마지막
	 * assistant 메시지는 이후 {@link #withMessageIdAndFeedback}·{@link #withFollowUpsAndReferences}로
	 * 채워진다. */
	public static AiConversationMessage of(String role, String content, String presetId, OffsetDateTime createdAt) {
		return new AiConversationMessage(role, content, presetId, null, null, createdAt, null, null);
	}

	/** messageId·저장된 피드백을 실어 재구성한다 - assistant 메시지에만 호출된다. */
	public AiConversationMessage withMessageIdAndFeedback(String messageId, Feedback feedback) {
		return new AiConversationMessage(role, content, presetId, messageId, feedback, createdAt, followUps,
				references);
	}

	/** 후속질문·참조를 실어 재구성한다 - 대화 전체의 마지막 assistant 메시지에만 호출된다. */
	public AiConversationMessage withFollowUpsAndReferences(JsonNode followUps, JsonNode references) {
		return new AiConversationMessage(role, content, presetId, messageId, feedback, createdAt, followUps,
				references);
	}

	/** 저장된 피드백(👍👎, 2026-09-02) - value는 "up"/"down", comment는 선택 코멘트(없으면 null),
	 * at은 저장 시각. 피드백을 저장·조회하는 API({@code V1BrandAiFeedbackController})와 응답 모양을
	 * 공유한다. */
	public record Feedback(String value, String comment, OffsetDateTime at) {
	}
}
