package com.celfit.was.v1.brandmonitoring.ai;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 대화 목록 응답 1건(FE 변경요청서 2026-08-28 §8). id·accountIds는 프론트 계약대로 문자열이다 -
 * bigint를 그대로 내려보내면 JS Number 정밀도 손실 위험이 있어서다(브랜드 계정 accountId와 동일 관용구).
 */
public record AiConversationSummary(String id, String title, List<String> accountIds,
		OffsetDateTime updatedAt, int messageCount) {

	public static AiConversationSummary from(AiConversationRepository.ConversationSummaryRow row, long brandId) {
		return new AiConversationSummary(String.valueOf(row.id()), row.title(),
				List.of(String.valueOf(brandId)), row.updatedAt(), row.messageCount());
	}
}
