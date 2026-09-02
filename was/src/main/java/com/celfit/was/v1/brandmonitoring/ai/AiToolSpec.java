package com.celfit.was.v1.brandmonitoring.ai;

/**
 * function calling 툴 선언 1건(설계 §4).
 *
 * @param parametersJson OpenAPI 스키마 부분집합 JSON 문자열. 인자가 없는 툴은 null - Gemini는
 *                       properties가 빈 object 스키마를 거부하는 버전이 있어 아예 필드를 생략한다.
 */
public record AiToolSpec(String name, String description, String parametersJson) {
}
