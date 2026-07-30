package com.celfit.was.monitoring;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/** app.monitoring_campaigns 1행(프론트 Campaign, 6.25). name이 유저별 라우트 키. */
public record CampaignRow(long id, long userId, String name, String description, LocalDate startDate,
		LocalDate endDate, String brand, Long budget, OffsetDateTime createdAt) {
}
