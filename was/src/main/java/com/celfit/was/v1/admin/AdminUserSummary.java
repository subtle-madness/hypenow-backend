package com.celfit.was.v1.admin;

/**
 * GET /v1/admin/users 목록 행(설계 2026-08-01 §4) — GET /v1/admin/users/{id}(AdminUserDetail)도
 * 이 필드 전부를 그대로 포함한다("Summary 전체", 플랫 JSON으로 통합). health는
 * AdminMonitoringHealthService가 users·registrations 양쪽에 공유하는 단일 유도를 쓴다.
 * companyName은 상세와 동일하게 null이면 ""(app.users.company_name이 NOT NULL DEFAULT ''라
 * 방어적 변환일 뿐, 실제로는 항상 DB의 ''가 그대로 나간다 — 08-02 프론트 companyName 추가 요청).
 */
public record AdminUserSummary(String id, String email, String name, String companyName, String userType,
		String signupRoute, String createdAt, String lastActiveAt, long campaignCount, long monitoringCount,
		long savedCount, String health) {
}
