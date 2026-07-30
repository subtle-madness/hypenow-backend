package com.celfit.was.v1.monitoring;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonUnwrapped;

/**
 * PATCH /v1/monitoring/items/{itemId} 응답(6.29) — 스펙 본문은 "수정된 TrackingItem"이라 item
 * 필드를 최상위로 펼치고(@JsonUnwrapped), campaignName으로 캠페인이 새로 생성된 경우에만 형제 키
 * campaign을 동봉한다(6.27 MonitoringRegistrationResponse와 동일 규약).
 */
public record MonitoringItemPatchResponse(
		@JsonUnwrapped TrackingItemResponse item,
		@JsonInclude(JsonInclude.Include.NON_NULL) CampaignResponse campaign) {

	public static MonitoringItemPatchResponse of(TrackingItemResponse item, CampaignResponse newlyCreatedCampaign) {
		return new MonitoringItemPatchResponse(item, newlyCreatedCampaign);
	}
}
