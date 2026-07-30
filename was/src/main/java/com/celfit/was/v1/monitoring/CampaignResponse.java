package com.celfit.was.v1.monitoring;

import com.celfit.was.monitoring.CampaignRow;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 캠페인 API 응답(스펙 6.25 Campaign). id는 문자열(String.valueOf), 날짜 필드는 기존 관용구(다른
 * 어셈블러들)를 따라 LocalDate.toString()으로 `yyyy-MM-dd` 문자열화한다. createdAt은 KST 오프셋
 * (+09:00)의 ISO 8601 문자열 — 서버 표준시(UTC)를 그대로 내리면 프론트가 9시간 어긋난 시각을 표시한다.
 * OffsetDateTime.toString()은 초가 0이면 생략해(`09:00+09:00`) 스펙 예시(`09:00:00+09:00`)와 어긋나므로
 * 초를 항상 명시하는 포맷터를 쓴다. nullable 필드(description·startDate·endDate·brand·budget)는
 * 계약 무결성 규칙 #1(1.8)에 따라 키를 생략하지 않고 명시적 null로 직렬화한다 — ApiResponse와 달리
 * 이 record엔 NON_NULL이 없다(기본값).
 */
public record CampaignResponse(String id, String name, String description, String startDate, String endDate,
		String brand, Long budget, String createdAt) {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");
	private static final DateTimeFormatter KST_ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

	public static CampaignResponse from(CampaignRow row) {
		return new CampaignResponse(
				String.valueOf(row.id()),
				row.name(),
				row.description(),
				row.startDate() == null ? null : row.startDate().toString(),
				row.endDate() == null ? null : row.endDate().toString(),
				row.brand(),
				row.budget(),
				KST_ISO.format(row.createdAt().atZoneSameInstant(KST)));
	}
}
