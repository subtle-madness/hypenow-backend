package com.celfit.was.v1.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CampaignPairingTest {

	@Test
	void 이름이_있으면_id를_그대로_유지한다() {
		assertThat(CampaignPairing.pair(5L, "캠페인")).isEqualTo(5L);
	}

	@Test
	void 이름이_없으면_id도_null로_떨어뜨린다() {
		assertThat(CampaignPairing.pair(5L, null)).isNull();
	}

	@Test
	void 둘_다_null이면_null() {
		assertThat(CampaignPairing.pair(null, null)).isNull();
	}
}
