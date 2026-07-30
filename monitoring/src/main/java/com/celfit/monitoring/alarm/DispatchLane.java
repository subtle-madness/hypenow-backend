package com.celfit.monitoring.alarm;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;

/**
 * 발송 레인 — 이벤트를 언제 메일로 내보낼지(스펙 §1-5).
 *
 * <p>즉시 레인은 직접 등록發 수집 시작 전용이다(시딩 수십 건은 디바운스가 1통으로 묶는다).
 * 나머지는 아침 레인: **적재 시점 기준 당일 09:00 KST**로 고정한다. 이미 지났으면 그 시각을
 * 그대로 저장해 다음 틱에 바로 due가 된다 — 다음 날로 미루면 새벽 스윕(02:00)이 만든 이벤트가
 * 하루 늦게 나가고, "오늘 아침에 알림" 기대가 깨진다.
 */
public final class DispatchLane {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");
	private static final LocalTime MORNING = LocalTime.of(9, 0);

	private DispatchLane() {
	}

	public static Instant immediate(Instant occurredAt) {
		return occurredAt;
	}

	public static Instant morning(Instant occurredAt) {
		return occurredAt.atZone(KST).toLocalDate().atTime(MORNING).atZone(KST).toInstant();
	}
}
