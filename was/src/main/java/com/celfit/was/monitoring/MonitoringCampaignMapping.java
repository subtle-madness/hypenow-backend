package com.celfit.was.monitoring;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * app.monitoring_campaigns 1행 — targetId는 monitoring DB target.id의 논리 참조(FK 아님).
 * targetId가 null이면 등록 2단계가 끝나지 않은 pending 행.
 */
public record MonitoringCampaignMapping(long id, long userId, UUID registrationKey,
		Long targetId, OffsetDateTime createdAt) {
}
