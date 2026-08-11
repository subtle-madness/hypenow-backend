package com.celfit.was.v1.admin;

import java.time.LocalDate;

/**
 * GET /v1/admin/campaigns의 status 유도(프론트 변경요청서 4-2-6절, 08-02) — DB 접근 없는 순수
 * 함수라 {@code AdminMonitoringStatus}와 같은 방식으로 경계값을 단위 테스트한다. 기준 날짜는 KST
 * 오늘이며 SQL이 아니라 호출부(AdminCampaignsController)가 {@code LocalDate.now(KstTimestamps.KST)}로
 * 넘긴다.
 */
public final class AdminCampaignStatus {

	public static final String PENDING = "pending";
	public static final String ACTIVE = "active";
	public static final String ENDED = "ended";
	public static final String NO_DATE = "no_date";

	private AdminCampaignStatus() {
	}

	/**
	 * start_date·end_date 둘 다 null이면 no_date, 오늘이 start_date 이전이면 pending, 오늘이
	 * end_date 이후면 ended, 그 외(기간 안이거나 한쪽만 설정돼 위 조건에 안 걸리면)는 active.
	 * 경계값은 active로 포함한다 — 오늘==start_date, 오늘==end_date 둘 다 active.
	 */
	public static String deriveStatus(LocalDate startDate, LocalDate endDate, LocalDate today) {
		if (startDate == null && endDate == null) {
			return NO_DATE;
		}
		if (startDate != null && today.isBefore(startDate)) {
			return PENDING;
		}
		if (endDate != null && today.isAfter(endDate)) {
			return ENDED;
		}
		return ACTIVE;
	}
}
