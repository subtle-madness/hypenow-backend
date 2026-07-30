package com.celfit.monitoring.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;

/**
 * 스윕 크론 배선 — 스케줄 실동작(트리거 시각에 스레드가 뜨는지)은 스프링 몫이라 검증 대상이 아니다.
 * 여기서 지키는 건 우리가 틀릴 수 있는 세 가지다: 운영 크론 문자열이 실제로 KST 02:00을 가리키는지,
 * 애노테이션 기본값이 "비활성"인지(잘못 켜지면 등록 직후 계정에 콜이 나간다), 위임이 붙어 있는지.
 */
class SweepSchedulerTest {

	/** 운영에 env로 주입할 크론 — UTC 17:00이 KST 02:00이라는 전제를 못박는다. */
	@Test
	void 운영_크론은_유효하고_UTC_17시는_KST_새벽_2시다() {
		CronExpression cron = CronExpression.parse("0 0 17 * * *");

		ZonedDateTime next = cron.next(ZonedDateTime.parse("2026-07-29T00:00:00Z"));

		assertThat(next).isEqualTo(ZonedDateTime.parse("2026-07-29T17:00:00Z"));
		// KST는 서머타임이 없어 연중 고정 +09:00이다 — 그래서 UTC 크론 하나로 KST 02:00이 유지된다.
		assertThat(next.withZoneSameInstant(ZoneId.of("Asia/Seoul")).toLocalDateTime())
				.isEqualTo("2026-07-30T02:00");
	}

	/**
	 * 기본값은 비활성이어야 한다 — 프로퍼티를 안 준 환경(로컬·테스트·신규 배포)에서 스윕이 저절로 돌면
	 * 등록된 전 계정에 Hiker 콜이 나간다. zone도 함께 못박는다: 빠지면 서버 로컬 타임존을 타
	 * 같은 크론이 머신마다 다른 시각에 돈다.
	 */
	@Test
	void 스케줄_애노테이션은_기본_비활성이고_UTC_기준이다() throws Exception {
		Scheduled scheduled = SweepScheduler.class.getMethod("sweep").getAnnotation(Scheduled.class);

		assertThat(scheduled).isNotNull();
		assertThat(scheduled.cron())
				.isEqualTo("${monitoring.schedule.sweep-cron:" + Scheduled.CRON_DISABLED + "}");
		assertThat(scheduled.zone()).isEqualTo("UTC");
	}

	@Test
	void sweep은_일일_스윕_잡에_위임한다() {
		var calls = new int[1];
		var job = new DailySweepJob(null, null, null, null, 0, Duration.ZERO) {
			@Override
			public void run() {
				calls[0]++;
			}
		};

		new SweepScheduler(job, new SweepGuard()).sweep();

		assertThat(calls[0]).isEqualTo(1);
	}

	/** 가드가 이미 잡혀 있으면(수동 트리거 진행 중 등) 크론 틱은 스윕을 건드리지 않고 스킵한다. */
	@Test
	void 가드가_이미_잡혀있으면_크론_스윕은_스킵된다() {
		var calls = new int[1];
		var job = new DailySweepJob(null, null, null, null, 0, Duration.ZERO) {
			@Override
			public void run() {
				calls[0]++;
			}
		};
		var guard = new SweepGuard();
		assertThat(guard.tryAcquire()).isTrue();   // 수동 트리거가 이미 잡고 있다고 흉내

		new SweepScheduler(job, guard).sweep();

		assertThat(calls[0]).isZero();
	}
}
