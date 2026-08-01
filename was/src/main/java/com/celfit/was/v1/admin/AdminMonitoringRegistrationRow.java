package com.celfit.was.v1.admin;

/**
 * GET /v1/admin/monitoring/registrations rows[] 1건(설계 2026-08-01 §4) — id는 monitoring_items.id.
 * campaignName은 미지정이어도 null 대신 "(캠페인 미지정)", postUrl·lastCollectedAt은 nullable.
 */
public record AdminMonitoringRegistrationRow(String id, String userId, String userName, String campaignName,
		String handle, String contentType, String status, String lastCollectedAt, String postUrl) {
}
