package com.celfit.was.v1.admin;

import com.celfit.was.entitlement.OrganizationRepository.OverrideRow;
import tools.jackson.databind.JsonNode;

/** 기능 오버라이드 1건 — 어드민 상세·PUT 응답 공용. featureKey는 raw 문자열(FeatureKey.name()). */
public record OrganizationOverrideResponse(String featureKey, boolean enabled, JsonNode value) {

	public static OrganizationOverrideResponse from(OverrideRow row) {
		return new OrganizationOverrideResponse(row.featureKey(), row.enabled(), row.value());
	}
}
