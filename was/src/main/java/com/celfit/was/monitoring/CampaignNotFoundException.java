package com.celfit.was.monitoring;

/** (user, target) 매핑 없음 — 남의 캠페인이거나 존재하지 않는 target. was 소유 검증 실패. */
public class CampaignNotFoundException extends MonitoringException {

	public CampaignNotFoundException(long targetId) {
		super("캠페인 매핑 없음: targetId=" + targetId);
	}
}
