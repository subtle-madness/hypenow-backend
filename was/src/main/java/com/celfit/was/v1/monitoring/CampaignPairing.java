package com.celfit.was.v1.monitoring;

/**
 * campaignId·campaignName 짝 방어(계약 무결성 규칙 #3, 1.8) — "campaignId가 값이면 캠페인 목록
 * 응답에 반드시 그 캠페인이 존재해야 한다", "둘 다 null이거나 둘 다 값이며 한쪽만 채워 내리지
 * 않는다". campaignId는 있는데 이름 조회가 실패하면(캠페인 삭제 경합 등 이론상 케이스) campaignId도
 * 함께 null로 떨어뜨려 짝을 유지한다 — 어긋나면 프론트에 유령 캠페인 카드가 뜬다.
 */
final class CampaignPairing {

	private CampaignPairing() {
	}

	static Long pair(Long campaignId, String campaignName) {
		return campaignName == null ? null : campaignId;
	}
}
