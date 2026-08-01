package com.celfit.was.v1.admin;

import java.util.List;
import java.util.Map;

/**
 * GET /v1/admin/monitoring/registrations 응답(설계 2026-08-01 §4) — counts는 5키 전부 항상 포함,
 * status 필터는 미반영(userId 필터만 반영). 페이지네이션 없음(§0 A7 — 전량 반환).
 */
public record AdminMonitoringRegistrationsResponse(List<AdminMonitoringRegistrationRow> rows,
		Map<String, Long> counts) {
}
