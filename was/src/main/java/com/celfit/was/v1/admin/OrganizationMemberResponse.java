package com.celfit.was.v1.admin;

import com.celfit.was.entitlement.OrganizationRepository.MemberRow;

/** 조직 멤버 1건 — 어드민 상세·멤버 배정/수정 응답 공용. orgRole은 원문 그대로(MEMBER/ORG_ADMIN). */
public record OrganizationMemberResponse(String userId, String email, String orgRole) {

	public static OrganizationMemberResponse from(MemberRow row) {
		return new OrganizationMemberResponse(String.valueOf(row.userId()), row.email(), row.orgRole());
	}
}
