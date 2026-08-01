package com.celfit.was.v1.admin;

/**
 * 어드민 조회 API 공용 페이지네이션(설계 2026-08-01 §4) — page(1부터)/limit(기본 20, 최대 100)을
 * offset으로 정규화한다. GET /v1/admin/users·GET /v1/admin/audit-logs가 공유.
 */
public record AdminPageRequest(int page, int limit, int offset) {

	private static final int DEFAULT_LIMIT = 20;
	private static final int MAX_LIMIT = 100;

	/** page<1·limit 범위 밖·null은 전부 기본값으로 방어(계약 위반 대신 관대한 정규화). */
	public static AdminPageRequest of(Integer page, Integer limit) {
		int normalizedPage = (page == null || page < 1) ? 1 : page;
		int normalizedLimit = (limit == null) ? DEFAULT_LIMIT : Math.min(Math.max(limit, 1), MAX_LIMIT);
		return new AdminPageRequest(normalizedPage, normalizedLimit, (normalizedPage - 1) * normalizedLimit);
	}
}
