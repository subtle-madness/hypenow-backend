package com.celfit.analytics.admin;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class JobCostEstimatorTest {

	@Test
	void 단가_범위와_건수로_비용_범위를_계산() {
		var card = JobCostEstimator.CostCard.of(JobName.CLASSIFY, 100, "0.0122", "0.061", "노트");
		assertThat(card.targets()).isEqualTo(100);
		assertThat(card.minUsd()).isEqualByComparingTo("1.22");
		assertThat(card.maxUsd()).isEqualByComparingTo("6.10");
		assertThat(card.label()).isEqualTo(JobName.CLASSIFY.label());
	}

	@Test
	void 단가_미실측이면_비용은_null() {
		var card = JobCostEstimator.CostCard.of(JobName.ACCOUNT_ANALYZE, 7, null, null, "단가 미실측");
		assertThat(card.minUsd()).isNull();
		assertThat(card.maxUsd()).isNull();
		assertThat(card.targets()).isEqualTo(7);
	}
}
