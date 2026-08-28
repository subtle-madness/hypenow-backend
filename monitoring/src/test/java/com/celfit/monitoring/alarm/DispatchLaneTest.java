package com.celfit.monitoring.alarm;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/** 발송 레인 계산 — KST 09:00은 UTC 00:00이다(서머타임 없어 연중 고정). */
class DispatchLaneTest {

	@Test
	void 아침_레인은_적재_당일_KST_09시다() {
		// KST 02:10(새벽 스윕) = UTC 전날 17:10 → 같은 KST 날짜의 09:00 = UTC 00:00
		Instant sweep = Instant.parse("2026-07-29T17:10:00Z");

		assertThat(DispatchLane.morning(sweep)).isEqualTo(Instant.parse("2026-07-30T00:00:00Z"));
	}

	/** 09:00을 지나 적재된 이벤트는 다음 날로 미루지 않는다 — 그 시각 그대로 = 다음 틱에 즉시 due. */
	@Test
	void 이미_지난_09시는_그_시각_그대로_저장해_즉시_due가_된다() {
		Instant afternoon = Instant.parse("2026-07-30T05:00:00Z");   // KST 14:00

		Instant dispatchAfter = DispatchLane.morning(afternoon);

		assertThat(dispatchAfter).isEqualTo(Instant.parse("2026-07-30T00:00:00Z"));
		assertThat(dispatchAfter).isBefore(afternoon);
	}

	/** KST 자정 직후는 그날 09:00이지 전날 09:00이 아니다 — UTC 기준으로 계산하면 하루 어긋난다. */
	@Test
	void KST_자정_직후는_그날_09시다() {
		Instant justAfterMidnightKst = Instant.parse("2026-07-29T15:05:00Z");   // KST 07-30 00:05

		assertThat(DispatchLane.morning(justAfterMidnightKst))
				.isEqualTo(Instant.parse("2026-07-30T00:00:00Z"));
	}
}
