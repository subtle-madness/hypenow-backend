package com.celfit.analytics.mirror;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.celfit.contract.analysis.AccountAnalysis;
import com.celfit.contract.analysis.AccountSummary;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 통계 왜곡 가드(스펙 2026-07-30-perf-summary-statistical-guards-design.md §3-1·§6) 신규 필드
 * 9개(AccountSummary) + 1개(AccountAnalysis.copyVersion)의 이름 매핑을 못 박는다.
 * MirrorJob은 record 필드를 뷰/테이블 컬럼과 "이름은 toSnakeCase 변환·순서는 위치(인덱스)"로
 * 대조한다(verifyColumns) — 이름이 어긋나면 미러가 런타임에야 깨지므로 여기서 고정한다.
 */
class AccountSummaryStatGuardMappingTest {

	@Test
	void AccountSummary_신규_필드_9개가_스펙_컬럼명으로_정확히_변환된다() {
		Map<String, String> expected = new LinkedHashMap<>();
		expected.put("viewsSampleCount", "views_sample_count");
		expected.put("likesSampleCount", "likes_sample_count");
		expected.put("commentsSampleCount", "comments_sample_count");
		expected.put("reelsCount", "reels_count");
		expected.put("feedCount", "feed_count");
		expected.put("medianViews", "median_views");
		expected.put("medianErPct", "median_er_pct");
		expected.put("topViewsSharePct", "top_views_share_pct");
		expected.put("windowSpanDays", "window_span_days");

		expected.forEach((field, column) ->
				assertEquals(column, MirrorJob.toSnakeCase(field), "필드 " + field + " 변환 결과 불일치"));
	}

	@Test
	void AccountAnalysis_copyVersion_필드가_copy_version으로_변환된다() {
		assertEquals("copy_version", MirrorJob.toSnakeCase("copyVersion"));
	}

	@Test
	void AccountSummary_record_필드_순서_끝_9개가_스펙_컬럼_순서와_일치한다() {
		// V46(email)·V49(avg_hype_raw)·2026-07-30(avg_hype_score_precise)가 차례로 그 뒤에 필드를
		// 이어붙여 "끝 9개"가 이제 email·avg_hype_raw·avg_hype_score_precise 앞 구간이다 —
		// skip(...).limit(9)로 그 구간만 자른다.
		RecordComponent[] components = AccountSummary.class.getRecordComponents();
		String[] tail = Arrays.stream(components)
				.skip(components.length - 12)
				.limit(9)
				.map(RecordComponent::getName)
				.map(MirrorJob::toSnakeCase)
				.toArray(String[]::new);

		assertEquals(
				java.util.List.of("views_sample_count", "likes_sample_count", "comments_sample_count",
						"reels_count", "feed_count", "median_views", "median_er_pct",
						"top_views_share_pct", "window_span_days"),
				java.util.List.of(tail));
	}

	@Test
	void AccountSummary_record_마지막_필드가_avg_hype_score_precise이다() {
		// 2026-07-30(스펙 2026-07-30-hype-score-v3-decay-after-mapping-design.md §10) — 콘텐츠 출력
		// 매핑 반영 소수 표시값이 avg_hype_raw 뒤에 맨 끝으로 이어붙었다(CREATE OR REPLACE VIEW
		// 중간 삽입 불가 제약).
		RecordComponent[] components = AccountSummary.class.getRecordComponents();
		RecordComponent last = components[components.length - 1];

		assertEquals("avg_hype_score_precise", MirrorJob.toSnakeCase(last.getName()));
	}

	@Test
	void AccountSummary_record_끝에서_두번째_필드가_avg_hype_raw이다() {
		// V49(스펙 2026-07-30-hype-score-v3-decay-after-mapping-design.md §9 하위절) — 정렬 전용
		// raw 평균 컬럼. avg_hype_score_precise 도입 전에는 이 컬럼이 맨 끝이었다.
		RecordComponent[] components = AccountSummary.class.getRecordComponents();
		RecordComponent secondLast = components[components.length - 2];

		assertEquals("avg_hype_raw", MirrorJob.toSnakeCase(secondLast.getName()));
	}

	@Test
	void AccountSummary_record_끝에서_세번째_필드가_email이다() {
		RecordComponent[] components = AccountSummary.class.getRecordComponents();
		RecordComponent thirdLast = components[components.length - 3];

		assertEquals("email", MirrorJob.toSnakeCase(thirdLast.getName()));
	}

	@Test
	void AccountAnalysis_record_마지막_필드가_copy_version이다() {
		RecordComponent[] components = AccountAnalysis.class.getRecordComponents();
		RecordComponent last = components[components.length - 1];

		assertEquals("copy_version", MirrorJob.toSnakeCase(last.getName()));
	}
}
