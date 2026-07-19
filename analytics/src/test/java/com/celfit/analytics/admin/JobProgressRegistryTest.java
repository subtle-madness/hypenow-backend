package com.celfit.analytics.admin;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class JobProgressRegistryTest {

	@Test
	void 시작_보고_종료_스냅샷() {
		JobProgressRegistry registry = new JobProgressRegistry();
		registry.start(JobName.ANALYZE);
		registry.reporter(JobName.ANALYZE).report(3, 1, 450);

		JobProgressRegistry.Progress p = registry.snapshot(JobName.ANALYZE);
		assertThat(p.running()).isTrue();
		assertThat(p.processed()).isEqualTo(3);
		assertThat(p.failed()).isEqualTo(1);
		assertThat(p.total()).isEqualTo(450);
		assertThat(p.startedAt()).isNotNull();

		registry.finish(JobName.ANALYZE);
		assertThat(registry.snapshot(JobName.ANALYZE).running()).isFalse();
		// 종료 후에도 마지막 진행값은 보존 (피드 기록 전 참조용)
		assertThat(registry.snapshot(JobName.ANALYZE).processed()).isEqualTo(3);
	}

	@Test
	void 미시작_잡은_빈_스냅샷() {
		JobProgressRegistry registry = new JobProgressRegistry();
		JobProgressRegistry.Progress p = registry.snapshot(JobName.MIRROR);
		assertThat(p.running()).isFalse();
		assertThat(p.total()).isZero();
	}
}
