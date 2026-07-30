package com.celfit.was.monitoring;

import java.time.OffsetDateTime;

/**
 * monitoring DB alarm_event 1행(계약 §3, v2.1) — was는 다이제스트 집계에 필요한 컬럼만 읽는다.
 * event_type은 monitoring 어휘(대문자) 그대로 — 프론트 소문자 변환은 {@link MonitoringEventTypes#toFront}.
 */
public record AlarmEventRow(long id, long targetId, long userId, String eventType, OffsetDateTime occurredAt) {
}
