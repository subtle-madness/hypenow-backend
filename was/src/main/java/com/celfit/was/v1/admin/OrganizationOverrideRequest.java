package com.celfit.was.v1.admin;

import tools.jackson.databind.JsonNode;

/** PUT /v1/admin/organizations/{id}/overrides/{featureKey} 요청 본문 — value는 선택(파라미터 없는 on/off도 허용). */
public record OrganizationOverrideRequest(Boolean enabled, JsonNode value) {
}
