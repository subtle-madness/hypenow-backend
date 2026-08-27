package com.celfit.was.v1.perfdashboard;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.was.v1.perfdashboard.PerformanceContentAssembler.DashboardRef;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 인플루언서 집계 순수 함수 검증(스펙 2026-08-27 §4) — handle 그룹핑·결측 규칙·대표 표시값을
 * DB 없이 고정한다. 정렬·페이지네이션은 호출부 몫이라 여기서 검증하지 않는다.
 */
class PerformanceInfluencerAggregatorTest {

	@Test
	void 지표는_아는_값만_합산하고_하나도_모르면_null이다() {
		// A: views=100·likes=10(공개)·comments=1 / B: 피드(views null)·likes 숨김·comments 2 / C: 스냅샷 없음
		var rows = PerformanceInfluencerAggregator.aggregate(List.of(
				ref("a", "2026-08-06", true, 100L, 10L, false, 1L),
				ref("a", "2026-08-05", true, null, 999L, true, 2L),
				ref("a", "2026-08-04", false, null, null, false, null)));

		assertThat(rows).hasSize(1);
		var row = rows.get(0);
		assertThat(row.handle()).isEqualTo("a");
		assertThat(row.postCount()).isEqualTo(3);          // 스냅샷 없어도 센다
		assertThat(row.views()).isEqualTo(100L);           // 아는 값만
		assertThat(row.likes()).isEqualTo(10L);            // 숨김 제외
		assertThat(row.likesKnownCount()).isEqualTo(1);
		assertThat(row.comments()).isEqualTo(3L);          // 1+2 (숨김은 likes만 영향)
		assertThat(row.latestPostAt()).isEqualTo("2026-08-06");
	}

	@Test
	void 지표를_하나도_모르면_0이_아니라_null이다() {
		var rows = PerformanceInfluencerAggregator.aggregate(List.of(
				ref("a", "2026-08-06", false, null, null, false, null),
				ref("a", "2026-08-05", false, null, null, false, null)));

		var row = rows.get(0);
		assertThat(row.postCount()).isEqualTo(2);
		assertThat(row.views()).isNull();
		assertThat(row.likes()).isNull();
		assertThat(row.comments()).isNull();
		assertThat(row.likesKnownCount()).isZero();
		assertThat(row.ratedFollowers()).isNull();
		assertThat(row.ratedEngaged()).isNull();
	}

	@Test
	void 스냅샷_없는_ref의_지표는_합산과_rated에서_제외된다() {
		// 스냅샷 없는 행에 값이 실려 있어도(방어) 합산 대상이 아니다 — 규칙 4·5의 hasSnapshots 게이트.
		var rows = PerformanceInfluencerAggregator.aggregate(List.of(
				ref("a", "2026-08-06", true, 100L, 10L, false, 1L, 1000L),
				ref("a", "2026-08-05", false, 999L, 999L, false, 999L, 999L)));

		var row = rows.get(0);
		assertThat(row.postCount()).isEqualTo(2);
		assertThat(row.views()).isEqualTo(100L);
		assertThat(row.likes()).isEqualTo(10L);
		assertThat(row.comments()).isEqualTo(1L);
		assertThat(row.likesKnownCount()).isEqualTo(1);
		assertThat(row.ratedFollowers()).isEqualTo(1000L);
		assertThat(row.ratedEngaged()).isEqualTo(11L);
	}

	@Test
	void rated는_팔로워와_좋아요_댓글을_모두_아는_게시물만_게시물당_팔로워_1회로_합산한다() {
		var rows = PerformanceInfluencerAggregator.aggregate(List.of(
				ref("a", "2026-08-06", true, 100L, 10L, false, 1L, 1000L),   // 적격
				ref("a", "2026-08-05", true, 200L, 20L, false, 2L, 1000L),   // 적격
				ref("a", "2026-08-04", true, 300L, 30L, false, 3L, null),    // 팔로워 미상
				ref("a", "2026-08-03", true, 400L, 40L, true, 4L, 1000L),    // 좋아요 숨김
				ref("a", "2026-08-02", true, 500L, 50L, false, null, 1000L)));	// 댓글 미상

		var row = rows.get(0);
		assertThat(row.ratedFollowers()).isEqualTo(2000L);            // 게시물당 1회씩
		assertThat(row.ratedEngaged()).isEqualTo(33L);                // (10+1)+(20+2)
		assertThat(row.likesKnownCount()).isEqualTo(4);               // 숨김 1건만 빠진다
		assertThat(row.likes()).isEqualTo(110L);                      // 10+20+30+50
		assertThat(row.comments()).isEqualTo(10L);                    // 1+2+3+4
	}

	@Test
	void handle_미상_ref는_집계에서_빠진다() {
		var rows = PerformanceInfluencerAggregator.aggregate(List.of(
				ref(null, "2026-08-06", true, 100L, 10L, false, 1L),
				ref("", "2026-08-06", true, 100L, 10L, false, 1L),
				ref("   ", "2026-08-06", true, 100L, 10L, false, 1L)));

		assertThat(rows).isEmpty();
	}

	@Test
	void 대표_표시값은_업로드_최신_ref_우선_첫_non_null이다() {
		// 최신 ref는 displayName·profileImageUrl·followers 전부 null → 그다음 최신 ref 값 채택.
		// 업로드일 미상 ref는 마지막 순번이라 대표값을 가져가지 못한다.
		var rows = PerformanceInfluencerAggregator.aggregate(List.of(
				ref("a", null, true, null, null, false, null, 999L, null, null, "미상표시", "img-null"),
				ref("a", "2026-08-05", true, null, null, false, null, 500L, null, null, "뷰티러버", "img-05"),
				ref("a", "2026-08-06", true, null, null, false, null, null, null, null, null, null)));

		var row = rows.get(0);
		assertThat(row.displayName()).isEqualTo("뷰티러버");
		assertThat(row.profileImageUrl()).isEqualTo("img-05");
		assertThat(row.followers()).isEqualTo(500L);
	}

	@Test
	void displayName은_전부_null이면_handle로_폴백한다() {
		var rows = PerformanceInfluencerAggregator.aggregate(List.of(
				ref("beautylover", "2026-08-06", true, 100L, 10L, false, 1L)));

		var row = rows.get(0);
		assertThat(row.displayName()).isEqualTo("beautylover");
		assertThat(row.profileImageUrl()).isNull();
		assertThat(row.followers()).isNull();
	}

	@Test
	void brandAccountIds는_등장_순_distinct이고_미귀속은_안_실린다() {
		var rows = PerformanceInfluencerAggregator.aggregate(List.of(
				ref("a", "2026-08-06", true, null, null, false, null, null, null, "b2", null, null),
				ref("a", "2026-08-05", true, null, null, false, null, null, null, null, null, null),
				ref("a", "2026-08-04", true, null, null, false, null, null, null, "b1", null, null),
				ref("a", "2026-08-03", true, null, null, false, null, null, null, "b2", null, null),
				ref("b", "2026-08-02", true, null, null, false, null, null, null, null, null, null)));

		assertThat(rows.get(0).brandAccountIds()).containsExactly("b2", "b1");
		assertThat(rows.get(1).brandAccountIds()).isEmpty();
	}

	@Test
	void latestPostAt은_최신_업로드일이고_전부_미상이면_null이다() {
		var rows = PerformanceInfluencerAggregator.aggregate(List.of(
				ref("a", "2026-08-04", true, null, null, false, null),
				ref("a", "2026-08-07", true, null, null, false, null),
				ref("a", null, true, null, null, false, null),
				ref("b", null, true, null, null, false, null)));

		assertThat(rows.get(0).latestPostAt()).isEqualTo("2026-08-07");
		assertThat(rows.get(1).latestPostAt()).isNull();
	}

	@Test
	void sponsoredCount는_sponsored만_센다() {
		var rows = PerformanceInfluencerAggregator.aggregate(List.of(
				ref("a", "2026-08-06", true, null, null, false, null, null, "sponsored", null, null, null),
				ref("a", "2026-08-05", true, null, null, false, null, null, "sponsored", null, null, null),
				ref("a", "2026-08-04", true, null, null, false, null, null, "organic", null, null, null),
				ref("a", "2026-08-03", true, null, null, false, null, null, "unknown", null, null, null),
				ref("a", "2026-08-02", true, null, null, false, null, null, null, null, null, null)));

		var row = rows.get(0);
		assertThat(row.postCount()).isEqualTo(5);
		assertThat(row.sponsoredCount()).isEqualTo(2);
	}

	@Test
	void 같은_handle은_한_행으로_묶이고_결과는_handle_등장_순이다() {
		// ref의 handle은 이미 소문자 계약(PR ①) — 집계기는 재정규화 없이 그대로 키로 쓴다.
		var rows = PerformanceInfluencerAggregator.aggregate(List.of(
				ref("beautylover", "2026-08-06", true, 100L, null, false, null),
				ref("aromashop", "2026-08-05", true, 200L, null, false, null),
				ref("beautylover", "2026-08-04", true, 300L, null, false, null)));

		assertThat(rows).extracting(PerformanceInfluencerResponse::handle)
				.containsExactly("beautylover", "aromashop");
		assertThat(rows.get(0).postCount()).isEqualTo(2);
		assertThat(rows.get(0).views()).isEqualTo(400L);
		assertThat(rows.get(1).postCount()).isEqualTo(1);
	}

	/** 지표 축만 varying — 표시값·분류 필드는 기본값(null). */
	private static DashboardRef ref(String handle, String uploadedOn, boolean hasSnapshots,
			Long views, Long likes, boolean likesHidden, Long comments) {
		return ref(handle, uploadedOn, hasSnapshots, views, likes, likesHidden, comments, null);
	}

	/** 지표 + followers — rated 규칙 검증용. */
	private static DashboardRef ref(String handle, String uploadedOn, boolean hasSnapshots,
			Long views, Long likes, boolean likesHidden, Long comments, Long followers) {
		return ref(handle, uploadedOn, hasSnapshots, views, likes, likesHidden, comments, followers,
				null, null, null, null);
	}

	/** 전량 지정 — 집계와 무관한 나머지 필드(contentKey·source·status·campaignId)는 고정값. */
	private static DashboardRef ref(String handle, String uploadedOn, boolean hasSnapshots,
			Long views, Long likes, boolean likesHidden, Long comments, Long followers,
			String sponsorship, String brandAccountId, String displayName, String profileImageUrl) {
		return new DashboardRef("ck", "sc", "post", sponsorship, "collected",
				uploadedOn == null ? null : LocalDate.parse(uploadedOn), brandAccountId, null,
				handle, displayName, profileImageUrl, followers,
				views, likes, likesHidden, comments, hasSnapshots);
	}
}
