package com.celfit.was.v1.admin;

import java.time.LocalDate;

/** POST /v1/admin/organizations 요청 본문 — 검증·enum 파싱은 {@link OrganizationAdminService}가 전담. */
public record OrganizationCreateRequest(String name, String plan, LocalDate contractStart, LocalDate contractEnd) {
}
