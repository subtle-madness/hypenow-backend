package com.celfit.monitoring.alarm;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;

/**
 * 발송 크론 배선 — SweepScheduler와 같은 규율이다. 우리가 틀릴 수 있는 건 셋:
 * 기본값이 비활성인지(잘못 켜지면 개통 전 환경에서 실메일이 나간다), zone이 고정인지,
 * 그리고 운영에 넣을 5분 크론 문자열이 실제로 파싱되는지.
 */
class AlarmDispatchSchedulerTest {

	@Test
	void 운영_5분_크론은_유효하다() {
		CronExpression cron = CronExpression.parse("0 */5 * * * *");

		assertThat(cron.next(java.time.ZonedDateTime.parse("2026-07-30T00:01:00Z")))
				.isEqualTo(java.time.ZonedDateTime.parse("2026-07-30T00:05:00Z"));
	}

	@Test
	void 스케줄_애노테이션은_기본_비활성이고_UTC_기준이다() throws Exception {
		Scheduled scheduled = AlarmDispatchScheduler.class.getMethod("dispatch")
				.getAnnotation(Scheduled.class);

		assertThat(scheduled).isNotNull();
		assertThat(scheduled.cron())
				.isEqualTo("${monitoring.alarm.dispatch-cron:" + Scheduled.CRON_DISABLED + "}");
		assertThat(scheduled.zone()).isEqualTo("UTC");
	}

	@Test
	void dispatch는_발송_잡에_위임한다() {
		var calls = new int[1];
		var job = new AlarmDispatchJob(null, null, null, null, null, null, 5, null) {
			@Override
			public void run() {
				calls[0]++;
			}
		};

		new AlarmDispatchScheduler(job).dispatch();

		assertThat(calls[0]).isEqualTo(1);
	}
}
