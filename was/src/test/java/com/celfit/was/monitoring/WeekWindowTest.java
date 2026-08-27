package com.celfit.was.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** 주간 집계 창(월~일 KST) — 잡이 받는 명시적 기간 파라미터(설계 §8). */
class WeekWindowTest {

	@Test
	void 월요일_실행은_직전_주_월요일이_시작일이다() {
		WeekWindow window = WeekWindow.previousWeekOf(LocalDate.of(2026, 8, 24));   // 2026-08-24는 월요일

		assertThat(window.startDate()).isEqualTo(LocalDate.of(2026, 8, 17));
		assertThat(window.endDateInclusive()).isEqualTo(LocalDate.of(2026, 8, 23));
	}

	@Test
	void 같은_주_어느_요일에_돌려도_같은_창을_준다() {
		WeekWindow monday = WeekWindow.previousWeekOf(LocalDate.of(2026, 8, 24));
		WeekWindow sunday = WeekWindow.previousWeekOf(LocalDate.of(2026, 8, 30));   // 같은 주의 일요일

		assertThat(sunday).isEqualTo(monday);
	}

	@Test
	void 경계는_시작일_KST_자정_포함과_다음_월요일_KST_자정_배타다() {
		WeekWindow window = WeekWindow.previousWeekOf(LocalDate.of(2026, 8, 24));

		assertThat(window.from())
				.isEqualTo(OffsetDateTime.of(2026, 8, 17, 0, 0, 0, 0, ZoneOffset.ofHours(9)));
		assertThat(window.toExclusive())
				.isEqualTo(OffsetDateTime.of(2026, 8, 24, 0, 0, 0, 0, ZoneOffset.ofHours(9)));
	}

	@Test
	void 월요일이_아닌_시작일은_거부한다() {
		assertThatThrownBy(() -> new WeekWindow(LocalDate.of(2026, 8, 18)))
				.isInstanceOf(IllegalArgumentException.class);
	}
}
