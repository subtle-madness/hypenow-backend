package com.celfit.was.v1.monitoring;

import com.celfit.was.monitoring.CampaignRow;
import com.celfit.was.v1.common.KstTimestamps;

/**
 * 캠페인 API 응답(스펙 6.25 Campaign). id는 문자열(String.valueOf), 날짜 필드는 기존 관용구(다른
 * 어셈블러들)를 따라 LocalDate.toString()으로 `yyyy-MM-dd` 문자열화한다. createdAt은 KstTimestamps로
 * KST 오프셋(+09:00) ISO 8601 문자열화한다 — 서버 표준시(UTC)를 그대로 내리면 프론트가 9시간 어긋난
 * 시각을 표시한다(스펙 1.5). nullable 필드(description·startDate·endDate·brand·budget)는
 * 계약 무결성 규칙 #1(1.8)에 따라 키를 생략하지 않고 명시적 null로 직렬화한다 — ApiResponse와 달리
 * 이 record엔 NON_NULL이 없다(기본값).
 */
public record CampaignResponse(String id, String name, String description, String startDate, String endDate,
		String brand, Long budget, String createdAt) {

	public static CampaignResponse from(CampaignRow row) {
		return new CampaignResponse(
				String.valueOf(row.id()),
				row.name(),
				row.description(),
				row.startDate() == null ? null : row.startDate().toString(),
				row.endDate() == null ? null : row.endDate().toString(),
				row.brand(),
				row.budget(),
				KstTimestamps.toKstIso(row.createdAt()));
	}
}
