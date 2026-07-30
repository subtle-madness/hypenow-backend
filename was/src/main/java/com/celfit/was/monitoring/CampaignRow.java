package com.celfit.was.monitoring;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * app.monitoring_campaigns 1행(프론트 Campaign, 6.25). name이 유저별 라우트 키. seedingCount는
 * 등록된 추적 행 수와 무관한 유저 선언 입력값(이행률 모수) — 집계값이 아니다.
 */
public record CampaignRow(long id, long userId, String name, String description, LocalDate startDate,
		LocalDate endDate, String brand, Long budget, Integer seedingCount, OffsetDateTime createdAt) {
}
