package com.celfit.was.v1.brandmonitoring;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;

/** 다음 스윕 예정 시각 계산(스펙 §5-2 nextScheduledAt) — 경계(정각 전후·자정 넘김) 고정. */
class BrandAccountAssemblerTest {

	private static ZonedDateTime kst(String iso) {
		return ZonedDateTime.parse(iso);
	}

	@Test
	void 스윕_시각_전이면_오늘_그_시각이다() {
		assertThat(BrandAccountAssembler.nextScheduledAt(kst("2026-08-07T01:30:00+09:00"), 3))
				.isEqualTo("2026-08-07T03:00:00+09:00");
	}

	@Test
	void 스윕_시각_후면_내일_그_시각이다() {
		assertThat(BrandAccountAssembler.nextScheduledAt(kst("2026-08-07T03:00:01+09:00"), 3))
				.isEqualTo("2026-08-08T03:00:00+09:00");
	}

	@Test
	void 정각_동시각은_이미_지난_것으로_보고_내일로_민다() {
		// 03:00:00 정각에 조회하면 그 스윕은 이미 시작된 것으로 본다 — "곧 온다"가 아니라 "다음"이 정답.
		assertThat(BrandAccountAssembler.nextScheduledAt(kst("2026-08-07T03:00:00+09:00"), 3))
				.isEqualTo("2026-08-08T03:00:00+09:00");
	}

	@Test
	void 자정_직전이면_다음_날_스윕이다() {
		assertThat(BrandAccountAssembler.nextScheduledAt(kst("2026-08-07T23:59:59+09:00"), 3))
				.isEqualTo("2026-08-08T03:00:00+09:00");
	}

	@Test
	void UTC_입력도_KST_기준으로_환산된다() {
		// UTC 2026-08-07T18:30Z = KST 2026-08-08T03:30 → 그날 스윕은 지났다.
		assertThat(BrandAccountAssembler.nextScheduledAt(kst("2026-08-07T18:30:00Z"), 3))
				.isEqualTo("2026-08-09T03:00:00+09:00");
	}
}
