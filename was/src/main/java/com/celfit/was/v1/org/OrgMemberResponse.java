package com.celfit.was.v1.org;

import com.celfit.was.entitlement.OrganizationRepository.MemberRow;

/** GET /v1/org/members, POST/PATCH 응답 공용 — 어드민 표면(OrganizationMemberResponse)과 형상이 같지만
 * 패키지 경계상 별도 record로 둔다(관례: v1.admin·v1.org는 서로의 record를 참조하지 않는다). */
public record OrgMemberResponse(String userId, String email, String orgRole) {

	public static OrgMemberResponse from(MemberRow row) {
		return new OrgMemberResponse(String.valueOf(row.userId()), row.email(), row.orgRole());
	}
}
