package com.celfit.was.v1.monitoring;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * POST /v1/monitoring/items 응답(6.27) — {@code data}. {@code campaign}은 nullable 엔티티
 * 필드가 아니라 "이번 요청으로 새 캠페인이 생성됐을 때만 동봉하는" 부가 키라 계약 무결성
 * 규칙 #1(명시적 null, 1.8)의 적용 대상이 아니다: 6.27 본문은 "동봉하지 않을 거라면 재조회
 * 필요를 계약에 명시해야 한다"고만 요구하고, campaignId/campaignName 같은 필드 표의 nullable
 * 규약과는 별개다. 그래서 이 필드만 NON_NULL로 키 자체를 생략한다(신규 생성 아니면 미동봉).
 */
public record MonitoringRegistrationResponse(String registrationId, List<TrackingItemResponse> items,
		@JsonInclude(JsonInclude.Include.NON_NULL) CampaignResponse campaign) {

	public static MonitoringRegistrationResponse of(long registrationId, List<TrackingItemResponse> items,
			CampaignResponse newlyCreatedCampaign) {
		return new MonitoringRegistrationResponse(String.valueOf(registrationId), items, newlyCreatedCampaign);
	}
}
