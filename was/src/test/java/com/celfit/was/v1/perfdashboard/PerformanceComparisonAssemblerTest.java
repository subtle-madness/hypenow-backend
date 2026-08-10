package com.celfit.was.v1.perfdashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.celfit.was.monitoring.BrandReadRepository.BrandAccountRow;
import com.celfit.was.v1.monitoring.TrackingItemResponse;
import com.celfit.was.v1.perfdashboard.PerformanceComparisonAssembler.BucketRange;
import java.time.LocalDate;
import java.time.OffsetDateTime;
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

	// ---------- 계정 집계 ----------

	private static final List<BucketRange> RANGES =
			PerformanceComparisonAssembler.bucketRanges(LocalDate.parse("2026-08-10"));

	/** ready 계정(lastSweptAt 존재) — covered 전 구간 true의 기준 픽스처. */
	private static BrandAccountRow readyAccount() {
		return new BrandAccountRow(2L, "cclime.beauty", LocalDate.parse("2026-08-10"),
				OffsetDateTime.parse("2026-08-09T18:00:00Z"), OffsetDateTime.parse("2026-05-14T00:12:00Z"),
				OffsetDateTime.parse("2026-05-14T01:00:00Z"), null,
				4143L, 15L, 82L, "", "끌리메 뷰티", null, true, null, "ACTIVE");
	}

	private static TrackingItemResponse.SnapshotResponse snapshot(Long views, Long likes,
			boolean likesHidden, Long comments) {
		return new TrackingItemResponse.SnapshotResponse("2026-08-09", views, likes, likesHidden,
				comments, null, null, false, null);
	}

	/** 콘텐츠 픽스처 — 스냅샷 없이 만들면 post.snapshots는 빈 목록(관측 전무)이다. */
	private static PerformanceContentResponse content(String shortcode, String brandAccountId,
			String uploadedAt, Long followers, TrackingItemResponse.SnapshotResponse... snapshots) {
		PerformanceContentResponse.PerformancePostResponse post = uploadedAt == null ? null
				: new PerformanceContentResponse.PerformancePostResponse(
						"https://www.instagram.com/p/" + shortcode + "/", shortcode, "reels", uploadedAt,
						"", List.of(), null, null, List.of(snapshots), null, false, 0, List.of());
		return new PerformanceContentResponse(
				new PerformanceContentResponse.PerformanceItemResponse(shortcode, "url", "tracking",
						"handle", "이름", null, followers, null, null, null, null, "2026-01-01", 90,
						null, post, null),
				"tagged", "unknown", shortcode, List.of(), brandAccountId);
	}

	@Test
	void 업로드일이_구간_경계에_정확히_귀속된다() {
		var result = PerformanceComparisonAssembler.compare(readyAccount(), List.of(
				content("A", "2", "2026-08-04", 100L, snapshot(10L, 1L, false, 1L)),   // 1w 하한
				content("B", "2", "2026-08-03", 100L, snapshot(10L, 1L, false, 1L)),   // 1w_1m 상한
				content("C", "2", "2025-08-10", 100L, snapshot(10L, 1L, false, 1L)),   // 6m_12m 하한
				content("D", "2", "2025-08-09", 100L, snapshot(10L, 1L, false, 1L)),   // 12개월 밖 — 제외
				content("E", "2", null, 100L)),                                        // 업로드일 미상 — 제외
				RANGES);

		assertThat(result.brandAccountId()).isEqualTo("2");
		assertThat(result.username()).isEqualTo("cclime.beauty");
		assertThat(result.collectionStartedAt()).isEqualTo("2026-05-14T09:12:00+09:00");
		assertThat(result.buckets()).extracting("key", "contentCount").containsExactly(
				tuple("1w", 1),
				tuple("1w_1m", 1),
				tuple("1m_3m", 0),
				tuple("3m_6m", 0),
				tuple("6m_12m", 1));
	}

	@Test
	void 합계는_non_null만_더하고_전부_null이면_null이다() {
		var result = PerformanceComparisonAssembler.compare(readyAccount(), List.of(
				// views 87400+20, likes 2800+null, comments 320+8 — 피드(views null)는 결측 카운트로.
				content("A", "2", "2026-08-09", 400000L, snapshot(87400L, 2800L, false, 320L)),
				content("B", "2", "2026-08-08", 12000L, snapshot(20L, null, true, 8L)),
				content("C", "2", "2026-08-07", null, snapshot(null, 24L, false, null))),
				RANGES);

		var oneWeek = result.buckets().get(0);
		assertThat(oneWeek.contentCount()).isEqualTo(3);
		assertThat(oneWeek.views()).isEqualTo(87420L);
		assertThat(oneWeek.likes()).isEqualTo(2824L);
		assertThat(oneWeek.comments()).isEqualTo(328L);
		assertThat(oneWeek.followersSum()).isEqualTo(412000L);
		assertThat(oneWeek.viewsMissingCount()).isEqualTo(1);
		assertThat(oneWeek.likesHiddenCount()).isEqualTo(1);
		assertThat(oneWeek.followersMissingCount()).isEqualTo(1);

		// 0건 구간은 합 전부 null(0이 아니다 — FE 규칙 ③), 카운트는 0.
		var empty = result.buckets().get(2);
		assertThat(empty.contentCount()).isZero();
		assertThat(empty.views()).isNull();
		assertThat(empty.likes()).isNull();
		assertThat(empty.comments()).isNull();
		assertThat(empty.followersSum()).isNull();
		assertThat(empty.viewsMissingCount()).isZero();
	}

	@Test
	void 스냅샷이_없는_콘텐츠는_지표_결측으로_센다() {
		var result = PerformanceComparisonAssembler.compare(readyAccount(), List.of(
				content("A", "2", "2026-08-09", 100L)),   // 스냅샷 0개 — 관측 전무
				RANGES);

		var oneWeek = result.buckets().get(0);
		assertThat(oneWeek.contentCount()).isEqualTo(1);
		assertThat(oneWeek.views()).isNull();
		assertThat(oneWeek.viewsMissingCount()).isEqualTo(1);
		// 숨김은 관측이 있어야 셀 수 있다 — 스냅샷 자체가 없으면 hidden 아님.
		assertThat(oneWeek.likesHiddenCount()).isZero();
		assertThat(oneWeek.followersSum()).isEqualTo(100L);
	}

	@Test
	void 지표는_최신_스냅샷에서_읽는다() {
		var result = PerformanceComparisonAssembler.compare(readyAccount(), List.of(
				// 스냅샷은 날짜 오름차순 계약 — 마지막(08-09)이 최신이다.
				content("A", "2", "2026-08-09", 100L,
						new TrackingItemResponse.SnapshotResponse("2026-08-08", 50L, 5L, false, 2L,
								null, null, false, null),
						snapshot(70L, 7L, false, 3L))),
				RANGES);

		assertThat(result.buckets().get(0).views()).isEqualTo(70L);
		assertThat(result.buckets().get(0).likes()).isEqualTo(7L);
	}

	@Test
	void 스윕_완주_전_계정은_전_구간_covered_false다() {
		BrandAccountRow collecting = new BrandAccountRow(3L, "laperi_kr", null, null,
				OffsetDateTime.parse("2026-08-09T00:00:00Z"), null, null,
				null, null, null, "", "", null, null, null, "ACTIVE");

		var ready = PerformanceComparisonAssembler.compare(readyAccount(), List.of(), RANGES);
		var notReady = PerformanceComparisonAssembler.compare(collecting, List.of(), RANGES);

		assertThat(ready.buckets()).allSatisfy(b -> assertThat(b.covered()).isTrue());
		assertThat(notReady.buckets()).allSatisfy(b -> assertThat(b.covered()).isFalse());
	}
}
