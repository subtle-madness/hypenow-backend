package com.celfit.was.monitoring;

import com.celfit.was.v1.common.KstTimestamps;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 주간 다이제스트의 집계 창(월~일, KST) — 설계 §8 "주간 경계 유실" 대응. 잡은 벽시계에서
 * 창을 유도하는 대신 이 값을 명시적 파라미터로 받는다. 월요일 09:00 정시 실행과 그 주의
 * 따라잡기 틱이 전부 같은 창을 계산하므로, 몇 번을 다시 돌려도 같은 (user, 주 시작일) 행을
 * 멱등하게 덮어쓴다.
 *
 * <p>시작일은 항상 월요일이다 — 다이제스트 행의 digest_date가 곧 주 시작일이라(설계 §7)
 * 다른 요일이 섞이면 (user, digest_date) 유니크가 "주 1건" 계약을 더 이상 뜻하지 않는다.
 */
public record WeekWindow(LocalDate startDate) {

	public WeekWindow {
		if (startDate.getDayOfWeek() != DayOfWeek.MONDAY) {
			throw new IllegalArgumentException("주 시작일은 월요일이어야 한다: " + startDate);
		}
	}

	/** 기준 KST 날짜가 속한 주의 <b>직전</b> 주(월요일 시작). 주중 어느 날에 불러도 같은 값이다. */
	public static WeekWindow previousWeekOf(LocalDate kstToday) {
		return new WeekWindow(kstToday.with(DayOfWeek.MONDAY).minusWeeks(1));
	}

	/** 창의 마지막 날(일요일, 포함) — KST 날짜 구간 조회의 상한. */
	public LocalDate endDateInclusive() {
		return startDate.plusDays(6);
	}

	/** 창 시작(포함) — 월요일 KST 자정. timestamptz 컬럼 범위 조회용. */
	public OffsetDateTime from() {
		return startDate.atStartOfDay(KstTimestamps.KST).toOffsetDateTime();
	}

	/** 창 끝(배타) — 다음 월요일 KST 자정. 경계 이벤트가 두 주에 겹치지 않게 항상 배타로 쓴다. */
	public OffsetDateTime toExclusive() {
		return startDate.plusWeeks(1).atStartOfDay(KstTimestamps.KST).toOffsetDateTime();
	}
}
