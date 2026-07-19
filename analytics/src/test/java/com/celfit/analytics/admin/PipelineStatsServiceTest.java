package com.celfit.analytics.admin;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PipelineStatsServiceTest {

	@Test
	void 잔여와_배치상한으로_오늘예정과_소요일() {
		// 후보 25764, 완료 1213, 상한 450 → 잔여 24551, 오늘 450, 55일
		assertThat(PipelineStatsService.todayPlanned(25764, 1213, 450)).isEqualTo(450);
		assertThat(PipelineStatsService.daysToFull(25764, 1213, 450)).isEqualTo(55);
		// 잔여 0
		assertThat(PipelineStatsService.todayPlanned(100, 100, 450)).isZero();
		assertThat(PipelineStatsService.daysToFull(100, 100, 450)).isZero();
		// 상한 0 방어
		assertThat(PipelineStatsService.daysToFull(10, 0, 0)).isZero();
	}
}
