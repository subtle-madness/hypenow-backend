package com.celfit.was.v1.perfdashboard;

import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 성과 비교 집계 조립(스펙 2026-08-10) — 목록 API가 조립한 전량(필터 적용 후)을 받아 브랜드
 * 계정 × 5구간으로 합산한다. 구간 산출·합산은 전부 정적 순수 함수라 DB 없이 단위 테스트한다.
 */
@Component
public class PerformanceComparisonAssembler {

	/** 구간 1개(양끝 포함) — 업로드일이 [from, to]에 들면 귀속. */
	record BucketRange(String key, LocalDate from, LocalDate to) {
	}

	/**
	 * FE 표 그대로의 5구간(서로 안 겹침, 업로드일 기준·KST 달력일). 달력월 연산은
	 * {@link LocalDate#minusMonths}(말일 클램프)라 월말 기준일에도 경계가 역전되지 않는다.
	 */
	static List<BucketRange> bucketRanges(LocalDate today) {
		return List.of(
				new BucketRange("1w", today.minusDays(6), today),
				new BucketRange("1w_1m", today.minusMonths(1), today.minusDays(7)),
				new BucketRange("1m_3m", today.minusMonths(3), today.minusMonths(1).minusDays(1)),
				new BucketRange("3m_6m", today.minusMonths(6), today.minusMonths(3).minusDays(1)),
				new BucketRange("6m_12m", today.minusMonths(12), today.minusMonths(6).minusDays(1)));
	}
}
