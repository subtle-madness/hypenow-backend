package com.celfit.was.v1.perfdashboard;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.was.v1.perfdashboard.PerformanceComparisonAssembler.BucketRange;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 비교 집계 순수 함수 검증 — 구간 산출·귀속·합산 전부 DB 없이 고정한다(스펙 2026-08-10). */
class PerformanceComparisonAssemblerTest {

	// ---------- 구간 산출 ----------

	@Test
	void 구간_5개가_FE_표_정의대로_나온다() {
		List<BucketRange> ranges = PerformanceComparisonAssembler.bucketRanges(LocalDate.parse("2026-08-10"));

		assertThat(ranges).containsExactly(
				new BucketRange("1w", LocalDate.parse("2026-08-04"), LocalDate.parse("2026-08-10")),
				new BucketRange("1w_1m", LocalDate.parse("2026-07-10"), LocalDate.parse("2026-08-03")),
				new BucketRange("1m_3m", LocalDate.parse("2026-05-10"), LocalDate.parse("2026-07-09")),
				new BucketRange("3m_6m", LocalDate.parse("2026-02-10"), LocalDate.parse("2026-05-09")),
				new BucketRange("6m_12m", LocalDate.parse("2025-08-10"), LocalDate.parse("2026-02-09")));
	}

	@Test
	void 월말_클램프에도_구간이_겹치지_않는다() {
		// 3-31 기준: minusMonths(1)=2-28 — 클램프가 일어나는 날짜에서 경계 역전·겹침이 없어야 한다.
		List<BucketRange> ranges = PerformanceComparisonAssembler.bucketRanges(LocalDate.parse("2026-03-31"));

		for (int i = 0; i < ranges.size(); i++) {
			assertThat(ranges.get(i).from()).isBeforeOrEqualTo(ranges.get(i).to());
			if (i > 0) {
				assertThat(ranges.get(i).to()).isBefore(ranges.get(i - 1).from());
			}
		}
	}
}
