package com.celfit.was.v1.admin;

/** POST /v1/admin/organizations/{id}/members 요청 본문. */
public record OrganizationMemberAddRequest(Long userId, String orgRole) {
}
