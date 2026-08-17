package com.celfit.was.v1.org;

import com.celfit.was.entitlement.OrganizationRepository.OrganizationRow;
import java.time.LocalDate;

/** GET /v1/org 응답(설계 2026-08-17 §조직 셀프서비스) — 요청자 자신의 orgRole도 함께 내린다. */
public record OrgResponse(String name, String plan, LocalDate contractStart, LocalDate contractEnd,
		String myOrgRole) {

	public static OrgResponse from(OrganizationRow row, String myOrgRole) {
		return new OrgResponse(row.name(), row.plan().toLowerCase(), row.contractStart(), row.contractEnd(),
				myOrgRole);
	}
}
