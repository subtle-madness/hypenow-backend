package com.celfit.was.v1.monitoring;

import java.util.Map;

/**
 * GET·PATCH /v1/notification-settings 응답(스펙 6.33). content 하위에 이벤트 유형 4종 키가
 * 항상 전부 존재해야 한다(순서는 MonitoringEventTypes.EVENT_TYPES 고정 — LinkedHashMap으로 보존).
 */
public record NotificationSettingsResponse(Map<String, EventSetting> content) {

	public record EventSetting(boolean email) {
	}
}
