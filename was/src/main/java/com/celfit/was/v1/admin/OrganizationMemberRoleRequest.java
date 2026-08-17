package com.celfit.was.v1.admin;

/** PATCH /v1/admin/organizations/{id}/members/{userId} 요청 본문. */
public record OrganizationMemberRoleRequest(String orgRole) {
}
