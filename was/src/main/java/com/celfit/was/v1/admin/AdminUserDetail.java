package com.celfit.was.v1.admin;

import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * GET /v1/admin/users/{id} 응답(설계 2026-08-01 §4) — AdminUserSummary 필드 전부("Summary 전체")를
 * 플랫하게 포함하고 jobTitle·signupCode·events를 더한다. companyName은 08-02부터 Summary 자체가
 * 이미 들고 있어(목록·상세 companyName 표면 통일 요청) 그대로 물려받는다. companyName·jobTitle은
 * null이면 ""(계약 요지 — 빈 문자열, 키 생략 아님). featureOverrides(2026-08-31)는 /v1/me와 같은
 * 형상 — 비어 있어도 항상 객체이고 null이 되지 않는다.
 */
public record AdminUserDetail(String id, String email, String name, String companyName, String userType,
		String signupRoute, String createdAt, String lastActiveAt, long campaignCount, long monitoringCount,
		long savedCount, String health, String jobTitle, String signupCode, List<AdminUserEvent> events,
		JsonNode featureOverrides) {

	public static AdminUserDetail from(AdminUserSummary summary, String jobTitle, String signupCode,
			List<AdminUserEvent> events, JsonNode featureOverrides) {
		return new AdminUserDetail(summary.id(), summary.email(), summary.name(), summary.companyName(),
				summary.userType(), summary.signupRoute(), summary.createdAt(), summary.lastActiveAt(),
				summary.campaignCount(), summary.monitoringCount(), summary.savedCount(), summary.health(),
				jobTitle, signupCode, events, featureOverrides);
	}
}
