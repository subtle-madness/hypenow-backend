package com.celfit.analytics.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class JobConfigTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
			.withUserConfiguration(JobConfig.class);

	@Test
	void admin_on이면_게이트_off여도_잡_빈_정의가_있다() {
		runner.withPropertyValues("analytics.admin-enabled=true")
				.run(ctx -> {
					assertThat(ctx.getSourceApplicationContext()
							.containsBeanDefinition("commentClassificationJob")).isTrue();
					assertThat(ctx.getSourceApplicationContext()
							.containsBeanDefinition("contentAnalysisJob")).isTrue();
					assertThat(ctx.getSourceApplicationContext()
							.containsBeanDefinition("accountAnalysisJob")).isTrue();
				});
	}

	@Test
	void admin_off_게이트_off면_잡_빈이_없다() {
		runner.withPropertyValues("analytics.admin-enabled=false")
				.run(ctx -> {
					assertThat(ctx.getSourceApplicationContext()
							.containsBeanDefinition("commentClassificationJob")).isFalse();
					assertThat(ctx.getSourceApplicationContext()
							.containsBeanDefinition("contentAnalysisJob")).isFalse();
					assertThat(ctx.getSourceApplicationContext()
							.containsBeanDefinition("accountAnalysisJob")).isFalse();
				});
	}

	@Test
	void admin_off여도_개별_게이트_on이면_해당_잡_빈_정의가_있다() {
		runner.withPropertyValues("analytics.admin-enabled=false", "analytics.analyze-on-startup=true")
				.run(ctx -> {
					assertThat(ctx.getSourceApplicationContext()
							.containsBeanDefinition("contentAnalysisJob")).isTrue();
					assertThat(ctx.getSourceApplicationContext()
							.containsBeanDefinition("commentClassificationJob")).isFalse();
				});
	}
}
