package com.celfit.monitoring.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TargetStatusTest {

	@Test
	void WATCHING과_TRACKING만_활성() {
		assertThat(TargetStatus.WATCHING.active()).isTrue();
		assertThat(TargetStatus.TRACKING.active()).isTrue();
		assertThat(TargetStatus.EXPIRED.active()).isFalse();
		assertThat(TargetStatus.CANCELED.active()).isFalse();
		assertThat(TargetStatus.FAILED.active()).isFalse();
	}
}
