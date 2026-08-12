package com.celfit.was.v1.brandmonitoring;

import java.util.List;

/**
 * 브랜드 해시태그 제외 문자열 응답(스펙 2026-08-11 §2) — 자사 태그 오탐 방지용 문자열 전체.
 * GET 응답 셰이프. PUT 요청 바디는 컨트롤러의 {@code HashtagExclusionsRequest}(같은 셰이프, terms만).
 */
public record BrandHashtagExclusionsResponse(List<String> terms) {
}
