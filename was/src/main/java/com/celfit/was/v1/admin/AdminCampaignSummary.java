package com.celfit.was.v1.admin;

/**
 * GET /v1/admin/campaigns rows[] 1건(프론트 변경요청서 4-2-6절, 08-02). seedingTarget은
 * app.monitoring_campaigns.seeding_count(프론트 어휘가 target일 뿐 같은 값, nullable 그대로).
 * registrationCount는 캠페인에 배정된 app.monitoring_items 전량(취소 포함,
 * CampaignRepository.countItems와 동일 계약). userId는 다른 어드민 표면 관례를 따라 문자열.
 */
public record AdminCampaignSummary(String id, String name, String userId, String userName, String createdAt,
		String status, long registrationCount, Integer seedingTarget) {
}
