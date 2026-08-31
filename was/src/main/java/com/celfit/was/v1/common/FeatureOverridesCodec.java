package com.celfit.was.v1.common;

import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 유저별 기능 플래그(app.users.feature_overrides) jsonb ↔ 응답 노드 변환·검증(2026-08-31).
 * 조회 표면(/v1/me, GET /v1/admin/users/{id})과 쓰기 표면(PUT /v1/admin/users/{id}/features)이
 * 같은 규칙을 공유하도록 한 곳에 모은다 — DTO record는 순수하게 두고 파싱은 이 컴포넌트가 맡는
 * 리포 관용구(V1ContentReportAssembler·DigestAssembler와 같은 위치)를 따른다.
 *
 * <p><b>키 문자열은 검증하지 않는다</b> — 기능 목록·기본값의 정본은 프론트다. 백엔드는 값 타입만
 * boolean | string[]으로 좁힌다(그 외 400 VALIDATION_FAILED).
 */
@Component
public class FeatureOverridesCodec {

	private final ObjectMapper objectMapper;

	public FeatureOverridesCodec(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	/**
	 * jsonb 텍스트 → 응답 노드. 계약상 값은 항상 객체이고 null이 아니다 — 컬럼이
	 * NOT NULL DEFAULT '{}'라 정상 경로에선 null·비객체가 올 수 없지만, 배포 이전 세션이나 수동
	 * UPDATE로 어긋난 값이 들어와도 조회가 500으로 깨지지 않도록 빈 객체로 흡수한다.
	 */
	public JsonNode read(String json) {
		if (json == null || json.isBlank()) {
			return objectMapper.createObjectNode();
		}
		try {
			JsonNode node = objectMapper.readTree(json);
			return node.isObject() ? node : objectMapper.createObjectNode();
		} catch (JacksonException e) {
			return objectMapper.createObjectNode();
		}
	}

	/**
	 * 저장 전 검증 — 객체이고 값이 전부 boolean 또는 문자열 배열이어야 한다. 위반은
	 * 400 VALIDATION_FAILED. 통과하면 jsonb에 넣을 JSON 문자열을 돌려준다.
	 */
	public String validateAndSerialize(JsonNode overrides) {
		if (overrides == null || !overrides.isObject()) {
			throw V1ApiException.validation("overrides는 JSON 객체여야 해요.");
		}
		for (Map.Entry<String, JsonNode> entry : overrides.properties()) {
			requireSupportedValue(entry.getKey(), entry.getValue());
		}
		return objectMapper.writeValueAsString(overrides);
	}

	private static void requireSupportedValue(String key, JsonNode value) {
		if (value.isBoolean()) {
			return;
		}
		if (value.isArray()) {
			for (JsonNode element : value) {
				if (!element.isString()) {
					throw invalidValue(key);
				}
			}
			return;
		}
		throw invalidValue(key);
	}

	private static V1ApiException invalidValue(String key) {
		return V1ApiException.validation("'%s' 값은 boolean이거나 문자열 배열이어야 해요.".formatted(key));
	}
}
