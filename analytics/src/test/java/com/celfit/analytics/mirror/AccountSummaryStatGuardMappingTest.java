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
		// V46(email, 스펙 2026-07-30-influencer-email-from-bio)이 그 뒤에 한 필드 더 이어붙어
		// "끝 9개"가 이제 email 바로 앞 구간이다 — skip(...).limit(9)로 그 구간만 자른다.
		RecordComponent[] components = AccountSummary.class.getRecordComponents();
		String[] tail = Arrays.stream(components)
				.skip(components.length - 10)
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
	void AccountSummary_record_마지막_필드가_email이다() {
		// V46(스펙 2026-07-30-influencer-email-from-bio) — biography 정규식 파싱 컬럼이 맨 끝에 이어붙었다.
		RecordComponent[] components = AccountSummary.class.getRecordComponents();
		RecordComponent last = components[components.length - 1];

		assertEquals("email", MirrorJob.toSnakeCase(last.getName()));
	}

	@Test
	void AccountAnalysis_record_마지막_필드가_copy_version이다() {
		RecordComponent[] components = AccountAnalysis.class.getRecordComponents();
		RecordComponent last = components[components.length - 1];

		assertEquals("copy_version", MirrorJob.toSnakeCase(last.getName()));
	}
}
