package com.celfit.was.entitlement;

import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * 유저 1인의 최종 entitlement 판정 결과(설계 2026-08-17 §판정 로직) — plan 기본값 위에 조직 오버라이드를
 * 합성한 산출물. {@link EntitlementService#entitlementsFor}만이 이 record를 만든다.
 * 기능 게이트는 {@link #has}로 체크하고 미보유 시 403 FEATURE_NOT_AVAILABLE, 데이터 범위 차등은
 * {@code params}를 쿼리 인자로 주입한다(SQL/뷰에 plan 분기를 심지 않는다).
 */
public record Entitlements(Plan plan, Set<FeatureKey> enabled, Map<FeatureKey, JsonNode> params) {

	public boolean has(FeatureKey key) {
		return enabled.contains(key);
	}
}
