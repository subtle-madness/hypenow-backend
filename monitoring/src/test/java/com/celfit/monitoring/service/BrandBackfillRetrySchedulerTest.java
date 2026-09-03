package com.celfit.monitoring.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 열거 실패 재시도 스케줄 배선(2026-09, 결함 1) — {@code SweepSchedulerTest}와 같은 관용구. 여기서
 * 지키는 건: 애노테이션이 fixedDelay 방식(고정 지연)으로 5분 기본값을 가리키는지, 위임이 붙어
 * 있는지, {@link BrandSweepGuard}가 잡혀 있으면(= 02:00 야간 브랜드 스윕 진행 중) 틱 전체를
 * 스킵하는지(3중 가드 중 ② — 설계 §3-4).
 */
class BrandBackfillRetrySchedulerTest {

	@Test
	void 스케줄_애노테이션은_기본_5분_고정지연이다() throws Exception {
		Scheduled scheduled = BrandBackfillRetryScheduler.class.getMethod("tick").getAnnotation(Scheduled.class);

		assertThat(scheduled).isNotNull();
		assertThat(scheduled.fixedDelayString())
				.isEqualTo("${monitoring.brand.backfill-retry.interval:5m}");
	}

	@Test
	void tick은_재시도_잡에_위임한다() {
		var calls = new int[1];
		var job = new BrandBackfillRetryJob(null, null, 3, Duration.ofHours(6), Duration.ofMinutes(5), 5) {
			@Override
			public void run() {
				calls[0]++;
			}
		};

		new BrandBackfillRetryScheduler(job, new BrandSweepGuard()).tick();

		assertThat(calls[0]).isEqualTo(1);
	}

	/** 야간 브랜드 스윕이 진행 중이면(가드 획득 상태) 재시도 틱 전체를 스킵한다. */
	@Test
	void 브랜드_스윕이_진행중이면_재시도_틱은_스킵된다() {
		var calls = new int[1];
		var job = new BrandBackfillRetryJob(null, null, 3, Duration.ofHours(6), Duration.ofMinutes(5), 5) {
			@Override
			public void run() {
				calls[0]++;
			}
		};
		var guard = new BrandSweepGuard();
		assertThat(guard.tryAcquire()).isTrue();   // 야간 스윕이 이미 잡고 있다고 흉내

		new BrandBackfillRetryScheduler(job, guard).tick();

		assertThat(calls[0]).isZero();
	}
}
