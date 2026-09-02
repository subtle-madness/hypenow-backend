package com.celfit.was.v1.brandmonitoring.ai;

/**
 * 피드백 저장 요청(2026-09-02) - {@code PUT /v1/brand-monitoring/ai/messages/{messageId}/feedback}.
 *
 * @param value   "up" 또는 "down"만 허용(컨트롤러가 검증, 그 외 값은 400).
 * @param comment 선택 코멘트(500자 이내, 컨트롤러가 검증). 공백뿐이거나 비어 있으면 null로 저장한다.
 */
public record AiFeedbackRequest(String value, String comment) {
}
