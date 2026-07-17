package com.celfit.analytics.admin;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class JobLockTest {

	@Test
	void 같은_잡은_해제_전까지_재획득_불가() {
		JobLock lock = new JobLock();
		assertThat(lock.tryAcquire(JobName.MIRROR)).isTrue();
		assertThat(lock.tryAcquire(JobName.MIRROR)).isFalse();
		assertThat(lock.isRunning(JobName.MIRROR)).isTrue();
		lock.release(JobName.MIRROR);
		assertThat(lock.isRunning(JobName.MIRROR)).isFalse();
		assertThat(lock.tryAcquire(JobName.MIRROR)).isTrue();
	}

	@Test
	void 잡별_락은_독립() {
		JobLock lock = new JobLock();
		assertThat(lock.tryAcquire(JobName.MIRROR)).isTrue();
		assertThat(lock.tryAcquire(JobName.ANALYZE)).isTrue();
	}
}
