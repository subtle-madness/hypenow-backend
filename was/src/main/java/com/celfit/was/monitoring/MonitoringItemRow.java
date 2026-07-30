package com.celfit.was.monitoring;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * app.monitoring_items 1행(v3 추적 행, V15). keywords는 jsonb 원문을 ::text로 받은 것 —
 * 파싱은 호출부(어셈블러) 몫. targetId·campaignId는 null 가능(각각 pending·미배정).
 */
public record MonitoringItemRow(long id, long userId, String mode, UUID registrationKey,
		Long targetId, Long campaignId, String inputValue, String sourceUrl, String keywords,
		int trackingDays, LocalDate registeredOn, OffsetDateTime canceledAt, String canceledFrom,
		LocalDate startedNotifiedOn, OffsetDateTime createdAt) {
}
