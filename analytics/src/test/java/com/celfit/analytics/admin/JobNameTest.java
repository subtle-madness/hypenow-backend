package com.celfit.analytics.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class JobNameTest {

	@Test
	void slug은_소문자_하이픈() {
		assertThat(JobName.MIRROR.slug()).isEqualTo("mirror");
		assertThat(JobName.ACCOUNT_ANALYZE.slug()).isEqualTo("account-analyze");
		assertThat(JobName.LATE_BACKFILL_ANALYZE.slug()).isEqualTo("late-backfill-analyze");
	}

	@Test
	void fromSlug은_역변환() {
		assertThat(JobName.fromSlug("mirror")).isEqualTo(JobName.MIRROR);
		assertThat(JobName.fromSlug("account-analyze")).isEqualTo(JobName.ACCOUNT_ANALYZE);
	}

	@Test
	void fromSlug은_모르는_값에_예외() {
		assertThatThrownBy(() -> JobName.fromSlug("nope")).isInstanceOf(IllegalArgumentException.class);
	}
}
