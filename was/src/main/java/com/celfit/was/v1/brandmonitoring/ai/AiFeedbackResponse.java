package com.celfit.was.v1.brandmonitoring.ai;

/**
 * 피드백 저장 응답(2026-09-02) - {@code PUT /v1/brand-monitoring/ai/messages/{messageId}/feedback}.
 * feedback 필드는 대화 상세({@link AiConversationMessage#feedback})와 같은 모양을 공유한다.
 */
public record AiFeedbackResponse(String messageId, AiConversationMessage.Feedback feedback) {

	public static AiFeedbackResponse of(String messageId, AiChatFeedbackRepository.FeedbackRow row) {
		return new AiFeedbackResponse(messageId,
				new AiConversationMessage.Feedback(row.value(), row.comment(), row.at()));
	}
}
