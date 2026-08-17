package com.celfit.was.v1.org;

/** PATCH /v1/org/members/{userId} 요청 본문. */
public record OrgMemberRoleRequest(String orgRole) {
}
