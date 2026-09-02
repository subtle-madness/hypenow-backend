package com.celfit.was.v1.admin;

import tools.jackson.databind.JsonNode;

/**
 * PUT /v1/admin/users/{id}/features 응답(2026-08-31 유저별 기능 플래그) — 요청 본문과 같은
 * {"overrides": ...} 형상. 값은 요청 원문이 아니라 <b>DB에 저장된 값</b>이다(jsonb가 키 순서를
 * 정규화하고 중복 키를 제거하므로 입력과 문자열이 다를 수 있다).
 */
public record AdminUserFeaturesResponse(JsonNode overrides) {
}
