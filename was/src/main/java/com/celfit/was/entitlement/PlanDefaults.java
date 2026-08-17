package com.celfit.was.entitlement;

import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * plan → 기본 활성 키 집합·기본 파라미터(코드 상수, 설계 2026-08-17 §판정 로직). {@link FeatureKey}가 아직
 * 제품 키 0개라 현재는 어떤 plan을 넣어도 빈 값을 반환한다 — 계약 내용이 확정되면 이 표를 채운다.
 */
final class PlanDefaults {

	private PlanDefaults() {
	}

	static Set<FeatureKey> enabledFor(Plan plan) {
		return Set.of();
	}

	static Map<FeatureKey, JsonNode> paramsFor(Plan plan) {
		return Map.of();
	}
}
