package com.celfit.monitoring.alarm;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;

/**
 * 발송 레인 — 이벤트의 {@code dispatch_after}를 정한다.
 *
 * <p>2026-08-27 주간 개편으로 <b>레인은 하나만 남았다</b>(설계 §2 즉시 레인 폐지). 소비는
 * was의 주간 다이제스트 잡뿐이고 그 잡은 {@code occurred_at}의 주만 본다 - dispatch_after는
 * 이제 어느 소비자도 읽지 않지만 컬럼이 NOT NULL이라 값은 계속 채워야 한다(컬럼 정리는
 * expand-contract상 별도 릴리스).
 *
 * <p>아침 레인은 <b>적재 시점 기준 당일 09:00 KST</b>다. 이미 지났으면 그 시각을 그대로 저장한다.
 */
public final class DispatchLane {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");
	private static final LocalTime MORNING = LocalTime.of(9, 0);

	private DispatchLane() {
	}

	public static Instant morning(Instant occurredAt) {
		return occurredAt.atZone(KST).toLocalDate().atTime(MORNING).atZone(KST).toInstant();
	}
}
