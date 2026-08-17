package com.celfit.was.v1.admin;

import com.celfit.was.entitlement.OrganizationRepository.MemberRow;
import com.celfit.was.entitlement.OrganizationRepository.OrganizationRow;
import com.celfit.was.entitlement.OrganizationRepository.OverrideRow;
import java.time.LocalDate;
import java.util.List;

/** GET /v1/admin/organizations/{id} — 조직 + 멤버 목록 + 오버라이드 목록(계획 Task 3 계약). */
public record OrganizationDetailResponse(String id, String name, String plan, LocalDate contractStart,
		LocalDate contractEnd, String createdAt, List<OrganizationMemberResponse> members,
		List<OrganizationOverrideResponse> overrides) {

	public static OrganizationDetailResponse from(OrganizationRow row, List<MemberRow> members,
			List<OverrideRow> overrides) {
		OrganizationResponse summary = OrganizationResponse.from(row);
		return new OrganizationDetailResponse(summary.id(), summary.name(), summary.plan(), summary.contractStart(),
				summary.contractEnd(), summary.createdAt(),
				members.stream().map(OrganizationMemberResponse::from).toList(),
				overrides.stream().map(OrganizationOverrideResponse::from).toList());
	}
}
