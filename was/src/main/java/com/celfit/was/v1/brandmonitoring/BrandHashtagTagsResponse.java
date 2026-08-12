package com.celfit.was.v1.brandmonitoring;

import java.util.List;

/**
 * 브랜드 태그 셋 응답(태그 관리 API, 2026-08-12) — 활성 태그 전체.
 * GET 응답 셰이프. PUT 요청 바디는 컨트롤러의 {@code HashtagTagsRequest}(같은 셰이프, tags만).
 */
public record BrandHashtagTagsResponse(List<String> tags) {
}
