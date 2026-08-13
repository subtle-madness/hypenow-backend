package com.celfit.was.v1.admin;

import java.time.LocalDate;

/**
 * 세 구간(전체·이번 달·오늘) 누적기 — 유저별 사용량(2026-08-12)과 전역 비용(2026-08-13)이 공유한다.
 * 경계는 전부 KST 달력일이고, 미래 날짜 행(이론상)은 month·day에서 제외된다.
 *
 * <p>원래 AdminCrawlingUsageService의 중첩 클래스였으나 두 번째 소비자가 생겨 끌어올렸다 —
 * 구간 판정이 두 벌이 되면 한쪽만 고쳐지는 순간 같은 화면의 두 숫자가 다른 경계를 쓰게 된다.
 */
class PeriodSums {

	private final LocalDate today;
	private final LocalDate monthStart;
	private long total;
	private long month;
	private long day;

	PeriodSums(LocalDate today, LocalDate monthStart) {
		this.today = today;
		this.monthStart = monthStart;
	}

	void add(LocalDate calledOn, long calls) {
		total += calls;
		if (!calledOn.isBefore(monthStart) && !calledOn.isAfter(today)) {
			month += calls;
		}
		if (calledOn.equals(today)) {
			day += calls;
		}
	}

	/**
	 * 이미 구간별로 접힌 값을 그대로 더한다 — 총계용(전역 비용 API). 날짜를 다시 판정하지
	 * 않으므로 breakdown 합과 totals가 구조적으로 어긋날 수 없다.
	 */
	void addPreAggregated(long total, long month, long day) {
		this.total += total;
		this.month += month;
		this.day += day;
	}

	long total() {
		return total;
	}

	long month() {
		return month;
	}

	long day() {
		return day;
	}
}
