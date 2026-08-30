package com.celfit.was.monitoring;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * monitoring 알람 이벤트 어휘 변환(저장 대문자 → 프론트 소문자). 저장 어휘의 정본은 monitoring
 * 모듈의 {@code AlarmEventType}이고, 프론트 계약(다이제스트 items[].type)은 소문자다.
 * metrics_private↔METRICS_HIDDEN, content_issue↔CONTENT_UNAVAILABLE만 이름이 다르고
 * 나머지는 대소문자만 다르다.
 *
 * <p>2026-08-27 주간 개편으로 역방향(toStorage)과 "4종 완전체 순서"(EVENT_TYPES)는 소비자가
 * 사라졌다 — 알림 설정은 주간 토글 1개가 됐고(설계 §5), 다이제스트 항목 순서·문안의 정본은
 * {@link WeeklyDigestAssembler}다.
 */
public final class MonitoringEventTypes {

	private static final Logger log = LoggerFactory.getLogger(MonitoringEventTypes.class);

	private static final Map<String, String> STORAGE_TO_FRONT = Map.of(
			"COLLECTION_STARTED", "collection_started",
			"COLLECTION_ENDED", "collection_ended",
			"METRICS_HIDDEN", "metrics_private",
			"CONTENT_UNAVAILABLE", "content_issue");

	private MonitoringEventTypes() {
	}

	/**
	 * 저장(monitoring AlarmEventType) 대문자 어휘 → 프론트 소문자 어휘. 미지 값은 <b>경고 로그 후
	 * null</b>(2026-08-28 품질 리뷰 nit) — 유일한 호출부인 WeeklyDigestJob의 주간 집계가
	 * alarm_event에 5번째 유형이 추가되는 순간 예외로 통째로 죽지 않고 그 이벤트만 조용히
	 * 건너뛴다.
	 */
	public static String toFront(String storageType) {
		String front = STORAGE_TO_FRONT.get(storageType);
		if (front == null) {
			log.warn("알 수 없는 저장 이벤트 유형(무시) — {}", storageType);
		}
		return front;
	}
}
