package com.celfit.was.v1.admin;

/**
 * GET /v1/admin/brand-monitoring/accounts 응답 1행(2026-09-03 어드민 "등록된 브랜드 목록" 표) —
 * 행 단위는 <b>연결</b>이다({@code app.brand_monitorings} 활성 행 1개당 1행). 같은 브랜드 계정을
 * 여러 유저가 등록하면 그 계정이 유저 수만큼 행으로 나오고, 그때 {@code postCount}·
 * {@code crawlingCalls}(계정 단위 값)는 행마다 그대로 중복 표시된다 — 행 식별은
 * {@code accountId + user.id} 조합이다(상세는 계약 문서
 * docs/contracts/admin-brand-monitoring-accounts-api.md 참조).
 *
 * <p>{@code collectionStatus}는 monitoring이 꺼져 있거나(모니터링 서브시스템 비활성) 계정이
 * monitoring DB에서 아직 안 보이면 null이다 - 그 외엔 {@code BrandAccountAssembler.collectionStatus}와
 * 동일 유도(collecting|ready|error).
 */
public record AdminBrandAccountRow(String accountId, String username, String mode, User user, long postCount,
		CrawlingCalls crawlingCalls, String collectionStatus, int collectionMonths, String backfillCompletedAt,
		String registeredAt, String lastCollectedAt) {

	/** 등록한 유저 — name·orgName은 DB 빈 문자열 기본값을 null로 접어서 "값 없음"을 명시한다. */
	public record User(long id, String email, String name, String orgName) {
	}

	/** 계정 단위 Hiker 콜 합계 — month는 KST 이번 달 1일부터. */
	public record CrawlingCalls(long total, long month) {
	}
}
