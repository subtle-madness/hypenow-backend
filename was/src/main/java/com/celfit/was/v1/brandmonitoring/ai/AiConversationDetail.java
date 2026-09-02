package com.celfit.was.v1.brandmonitoring.ai;

import java.time.OffsetDateTime;
import java.util.List;

/** 대화 상세 응답(FE 변경요청서 2026-08-28 §8) - 목록 응답 필드 + 펼쳐진 메시지 전체. */
public record AiConversationDetail(String id, String title, List<String> accountIds,
		OffsetDateTime updatedAt, List<AiConversationMessage> messages) {

	public static AiConversationDetail of(AiConversationRepository.ConversationRow row,
			List<AiConversationMessage> messages) {
		return new AiConversationDetail(String.valueOf(row.id()), row.title(),
				List.of(String.valueOf(row.brandId())), row.updatedAt(), messages);
	}
}
