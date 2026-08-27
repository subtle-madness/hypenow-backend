package com.celfit.was.monitoring;

import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 모니터링 이벤트 유형 4종(스펙 6.32 다이제스트·6.33 알림 설정 공통, 순서 고정).
 * v1 알림 설정 API(NotificationSettingsService)와 후속 다이제스트 크론이 같은 4종·순서를
 * 참조해야 해서 monitoring 패키지(공통 레이어)에 둔다 — v1 API 서비스를 역참조하면
 * (크론 → v1) 레이어링이 꼬인다.
 *
 * <p>어휘 정본은 monitoring 모듈의 {@code AlarmEventType}(대문자) — app.monitoring_email_opt_outs의
 * CHECK 제약도 그 어휘를 쓴다(V15). 프론트 계약(6.33)은 소문자라서 이 클래스가 저장(대문자)과
 * 프론트(소문자) 경계에서 양방향 매핑을 제공한다. metrics_private↔METRICS_HIDDEN,
 * content_issue↔CONTENT_UNAVAILABLE만 이름이 다르고 나머지는 대소문자만 다르다.
 */
public final class MonitoringEventTypes {

	private static final Logger log = LoggerFactory.getLogger(MonitoringEventTypes.class);

	public static final List<String> EVENT_TYPES =
			List.of("collection_started", "collection_ended", "metrics_private", "content_issue");

	private static final Map<String, String> FRONT_TO_STORAGE = Map.of(
			"collection_started", "COLLECTION_STARTED",
			"collection_ended", "COLLECTION_ENDED",
			"metrics_private", "METRICS_HIDDEN",
			"content_issue", "CONTENT_UNAVAILABLE");

	private static final Map<String, String> STORAGE_TO_FRONT = Map.of(
			"COLLECTION_STARTED", "collection_started",
			"COLLECTION_ENDED", "collection_ended",
			"METRICS_HIDDEN", "metrics_private",
			"CONTENT_UNAVAILABLE", "content_issue");

	private MonitoringEventTypes() {
	}

	/** 프론트 소문자 어휘 → 저장(monitoring AlarmEventType) 대문자 어휘. 미지 값은 예외. */
	public static String toStorage(String frontType) {
		String storage = FRONT_TO_STORAGE.get(frontType);
		if (storage == null) {
			throw new IllegalArgumentException("알 수 없는 이벤트 유형: " + frontType);
		}
		return storage;
	}

	/**
	 * 저장(monitoring AlarmEventType) 대문자 어휘 → 프론트 소문자 어휘. 미지 값은 <b>경고 로그 후
	 * null</b>(2026-08-28 품질 리뷰 nit) — 유일한 호출부인 WeeklyDigestJob의 주간 집계가
	 * alarm_event에 5번째 유형이 추가되는 순간 예외로 통째로 죽지 않고 그 이벤트만 조용히
	 * 건너뛴다. 사용자 입력을 검증하는 반대 방향({@link #toStorage})은 여전히 예외를 던진다 —
	 * 그쪽은 잘못된 입력을 즉시 거부하는 게 맞다.
	 */
	public static String toFront(String storageType) {
		String front = STORAGE_TO_FRONT.get(storageType);
		if (front == null) {
			log.warn("알 수 없는 저장 이벤트 유형(무시) — {}", storageType);
		}
		return front;
	}
}
