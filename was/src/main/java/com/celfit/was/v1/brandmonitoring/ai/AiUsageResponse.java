package com.celfit.was.v1.brandmonitoring.ai;

import java.time.OffsetDateTime;

/**
 * 챗 사용량 응답(FE 변경요청서 2026-08-28 §9.2) - 오늘 상한·잔여 횟수·다음 초기화 시각.
 * remaining은 이미 0 이하로 내려가지 않게 보정돼 있다({@link AiChatQuota#usage(long)}).
 */
public record AiUsageResponse(int dailyLimit, int remaining, OffsetDateTime resetAt) {

	public static AiUsageResponse from(AiChatQuota.Usage usage) {
		return new AiUsageResponse(usage.dailyLimit(), usage.remaining(), usage.resetAt());
	}
}
