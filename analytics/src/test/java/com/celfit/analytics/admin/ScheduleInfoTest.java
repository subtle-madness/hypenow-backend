package com.celfit.analytics.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.ZonedDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class ScheduleInfoTest {

	@Test
	void 크론_다음_발화를_KST로() {
		ScheduleInfo info = new ScheduleInfo(true, "0 30 19 * * *", "-", "-", "-", "-", "-");
		ZonedDateTime base = ZonedDateTime.of(2026, 7, 19, 10, 0, 0, 0, ZoneId.of("UTC"));
		// UTC 19:30 = KST 04:30 익일
		assertThat(info.next(JobName.MIRROR, base))
				.hasValueSatisfying(t -> {
					assertThat(t.getZone()).isEqualTo(ZoneId.of("Asia/Seoul"));
					assertThat(t.getHour()).isEqualTo(4);
					assertThat(t.getMinute()).isEqualTo(30);
				});
		assertThat(info.next(JobName.ANALYZE, base)).isEmpty(); // "-" = 비활성
	}

	@Test
	void 비활성이면_전부_빈값() {
		ScheduleInfo info = new ScheduleInfo(false, "0 30 19 * * *", "-", "-", "-", "-", "-");
		assertThat(info.next(JobName.MIRROR, ZonedDateTime.now())).isEmpty();
		assertThat(info.enabled()).isFalse();
	}

	@Test
	void fact_analyze_크론도_KST로_계산된다() {
		// 운영 기본: UTC 20:00 = KST 05:00 (파트 A). 파트 B는 30분 뒤다.
		ScheduleInfo info = new ScheduleInfo(true, "-", "-", "0 30 20 * * *", "-", "-", "0 0 20 * * *");
		ZonedDateTime base = ZonedDateTime.of(2026, 9, 3, 10, 0, 0, 0, ZoneId.of("UTC"));

		assertThat(info.next(JobName.FACT_ANALYZE, base))
				.hasValueSatisfying(t -> {
					assertThat(t.getHour()).isEqualTo(5);
					assertThat(t.getMinute()).isZero();
				});
		assertThat(info.next(JobName.ANALYZE, base))
				.hasValueSatisfying(t -> {
					assertThat(t.getHour()).isEqualTo(5);
					assertThat(t.getMinute()).isEqualTo(30);
				});
	}
}
