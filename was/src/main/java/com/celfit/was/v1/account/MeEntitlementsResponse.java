package com.celfit.was.v1.account;

import com.celfit.was.entitlement.Entitlements;
import com.celfit.was.entitlement.FeatureKey;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * GET /v1/me/entitlements 응답(설계 2026-08-17 §판정 로직 — "프론트가 소비할 수 있게 plan + 활성 키 +
 * 파라미터는 1차에 포함"). plan은 다른 v1 enum 노출 관례(UserProfile.role 등)를 따라 소문자로 내린다.
 */
public record MeEntitlementsResponse(String plan, List<String> features, Map<String, JsonNode> params) {

	public static MeEntitlementsResponse from(Entitlements entitlements) {
		List<String> features = entitlements.enabled().stream()
				.map(FeatureKey::name)
				.sorted()
				.toList();
		Map<String, JsonNode> params = new LinkedHashMap<>();
		entitlements.params().forEach((key, value) -> params.put(key.name(), value));
		return new MeEntitlementsResponse(entitlements.plan().name().toLowerCase(), features, params);
	}
}
