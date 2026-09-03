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

	/**
	 * offset 기반 정규화(2026-09-03 어드민 브랜드 목록 계정 API §GET /v1/admin/brand-monitoring/accounts) —
	 * offset<0·null은 0, limit 규칙은 {@link #of}와 동일. page는 offset을 역산한 근사값이라 호출부가
	 * 굳이 쓰지 않아도 되지만, 레코드 완전성을 위해 채워 둔다.
	 */
	public static AdminPageRequest ofOffset(Integer offset, Integer limit) {
		int normalizedLimit = (limit == null) ? DEFAULT_LIMIT : Math.min(Math.max(limit, 1), MAX_LIMIT);
		int normalizedOffset = (offset == null || offset < 0) ? 0 : offset;
		int normalizedPage = (normalizedOffset / normalizedLimit) + 1;
		return new AdminPageRequest(normalizedPage, normalizedLimit, normalizedOffset);
	}
}
