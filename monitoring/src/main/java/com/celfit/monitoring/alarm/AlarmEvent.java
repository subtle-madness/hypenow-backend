package com.celfit.monitoring.alarm;

import java.time.Instant;

/**
 * alarm_event 한 행 — 알람의 단일 원천(메일 발송 + 앱 내 알림·히스토리).
 * payload는 문안 재료 JSON 문자열이다(username·shortCode·상세) — 발송기가 파싱해 쓴다.
 * email_attempts는 일부러 싣지 않는다: 상한 판정은 due 조회의 WHERE가 DB에서 끝내므로
 * 자바 쪽에 들고 오면 "읽고 비교하는" 두 번째 판정 지점이 생겨 둘이 어긋날 여지만 만든다.
 */
public record AlarmEvent(long id, long targetId, long userId, AlarmEventType eventType,
		String payload, Instant occurredAt, Instant dispatchAfter,
		AlarmEmailStatus emailStatus, Instant emailSentAt) {}
