package com.celfit.was.v1.admin;

import java.util.List;

/**
 * GET /v1/admin/users/{id} 응답(설계 2026-08-01 §4) — AdminUserSummary 필드 전부("Summary 전체")를
 * 플랫하게 포함하고 companyName·jobTitle·signupCode·events를 더한다. companyName·jobTitle은
 * null이면 ""(계약 요지 — 빈 문자열, 키 생략 아님).
 */
public record AdminUserDetail(String id, String email, String name, String userType, String signupRoute,
		String createdAt, String lastActiveAt, long campaignCount, long monitoringCount, long savedCount,
		String health, String companyName, String jobTitle, String signupCode, List<AdminUserEvent> events) {

	public static AdminUserDetail from(AdminUserSummary summary, String companyName, String jobTitle,
			String signupCode, List<AdminUserEvent> events) {
		return new AdminUserDetail(summary.id(), summary.email(), summary.name(), summary.userType(),
				summary.signupRoute(), summary.createdAt(), summary.lastActiveAt(), summary.campaignCount(),
				summary.monitoringCount(), summary.savedCount(), summary.health(), companyName, jobTitle,
				signupCode, events);
	}
}
