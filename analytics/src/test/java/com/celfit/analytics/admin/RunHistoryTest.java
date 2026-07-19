package com.celfit.analytics.admin;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RunHistoryTest {

	@Test
	void 최신순_반환_및_상한() {
		RunHistory history = new RunHistory(3);
		for (int i = 0; i < 5; i++) {
			history.record(new RunHistory.Run(JobName.MIRROR, TriggerType.MANUAL,
					java.time.Instant.ofEpochSecond(i), java.time.Instant.ofEpochSecond(i + 1),
					RunHistory.Outcome.SUCCESS, i, 0, null));
		}
		assertThat(history.recent(10)).hasSize(3);
		assertThat(history.recent(10).getFirst().processed()).isEqualTo(4); // 최신이 앞
		assertThat(history.recent(2)).hasSize(2);
	}
}
