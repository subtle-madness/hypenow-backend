package com.celfit.was.v1.brandmonitoring;

import java.util.List;

/**
 * 브랜드 시딩 계정 응답(스펙 2026-08-17 §6) — 브랜드가 등록한 협업 인플루언서 목록.
 * GET 응답 셰이프. PUT 요청 바디는 컨트롤러의 {@code SeededAccountsRequest}(같은 셰이프, usernames만).
 */
public record SeededAccountsResponse(List<String> usernames) {
}
