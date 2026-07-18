package com.celfit.analytics.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ScheduleRunnerTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
			.withBean(AnalyticsJobService.class, () -> mock(AnalyticsJobService.class))
			.withUserConfiguration(ScheduleRunner.class);

	@Test
	void 기본은_비활성() {
		runner.run(ctx -> assertThat(ctx).doesNotHaveBean(ScheduleRunner.class));
	}

	@Test
	void enabled면_활성() {
		runner.withPropertyValues("analytics.schedule.enabled=true")
				.run(ctx -> assertThat(ctx).hasSingleBean(ScheduleRunner.class));
	}
}
