package com.celfit.was.v1.admin;

/**
 * GET /v1/admin/users 목록 행(설계 2026-08-01 §4) — GET /v1/admin/users/{id}(AdminUserDetail)도
 * 이 필드 전부를 그대로 포함한다("Summary 전체", 플랫 JSON으로 통합). health는
 * AdminMonitoringHealthService가 users·registrations 양쪽에 공유하는 단일 유도를 쓴다.
 */
public record AdminUserSummary(String id, String email, String name, String userType, String signupRoute,
		String createdAt, String lastActiveAt, long campaignCount, long monitoringCount, long savedCount,
		String health) {
}
