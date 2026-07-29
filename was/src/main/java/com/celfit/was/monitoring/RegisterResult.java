package com.celfit.was.monitoring;

import tools.jackson.databind.JsonNode;

/**
 * 등록 응답(계약 §2-1). firstSnapshot은 타입별(profile/post 지표) 형태가 계약 v0.1에서
 * 미확정이라 불투명 JsonNode로 전달만 한다 — 성형은 프론트 API 작업 때.
 */
public record RegisterResult(long targetId, String status, JsonNode firstSnapshot) {
}
