package com.celfit.was.monitoring;

import java.util.List;

/**
 * 모니터링 이벤트 유형 4종(스펙 6.32 다이제스트·6.33 알림 설정 공통, 순서 고정).
 * v1 알림 설정 API(NotificationSettingsService)와 후속 다이제스트 크론이 같은 4종·순서를
 * 참조해야 해서 monitoring 패키지(공통 레이어)에 둔다 — v1 API 서비스를 역참조하면
 * (크론 → v1) 레이어링이 꼬인다.
 */
public final class MonitoringEventTypes {

	public static final List<String> EVENT_TYPES =
			List.of("collection_started", "collection_ended", "metrics_private", "content_issue");

	private MonitoringEventTypes() {
	}
}
