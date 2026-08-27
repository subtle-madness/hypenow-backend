package com.celfit.was.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 주간 다이제스트 항목 조립(설계 §3) — 섹션 순서·합산 지표·조회수 NULL 규칙·하이라이트. */
class WeeklyDigestAssemblerTest {

	private final WeeklyDigestAssembler assembler = new WeeklyDigestAssembler();

	private static WeeklyPostMetrics reels(String shortCode, String author, Long views, Long likes, Long comments) {
		return new WeeklyPostMetrics(shortCode, author, "REELS", views, likes, comments);
	}

	private static WeeklyPostMetrics feed(String shortCode, String author, Long views, Long likes, Long comments) {
		return new WeeklyPostMetrics(shortCode, author, "FEED", views, likes, comments);
	}

	private static WeeklyDigestInput input(Map<String, Long> eventCounts, List<WeeklyPostMetrics> brandNewPosts,
			List<WeeklyPostMetrics> endedPosts, List<String> adShortCodes, List<String> campaignNames) {
		return new WeeklyDigestInput(eventCounts, brandNewPosts, endedPosts, adShortCodes, campaignNames);
	}

	@Test
	void 아무것도_없으면_빈_목록이다() {
		assertThat(assembler.assemble(input(Map.of(), List.of(), List.of(), List.of(), List.of()))).isEmpty();
	}

	@Test
	void 섹션_순서는_확인필요_브랜드_모니터링_하이라이트다() {
		List<DigestItem> items = assembler.assemble(input(
				Map.of("collection_started", 1L, "collection_ended", 2L, "metrics_private", 3L, "content_issue", 4L),
				List.of(reels("B1", "author_b", 100L, 10L, 1L)),
				List.of(reels("E1", "author_e", 50L, 5L, 1L)),
				List.of("AD1"),
				List.of()));

		assertThat(items).extracting(DigestItem::type).containsExactly(
				"ad_not_disclosed", "content_issue", "metrics_private",
				"brand_new_posts",
				"collection_started", "collection_ended",
				"top_post");
		assertThat(items).extracting(DigestItem::category).containsExactly(
				"action_needed", "action_needed", "action_needed",
				"brand", "content", "content", "highlight");
	}

	@Test
	void 건수가_0인_항목은_아예_빠진다() {
		List<DigestItem> items = assembler.assemble(input(
				Map.of("collection_started", 2L), List.of(), List.of(), List.of(), List.of()));

		assertThat(items).extracting(DigestItem::type).containsExactly("collection_started");
		assertThat(items.get(0).count()).isEqualTo(2);
		assertThat(items.get(0).summary()).isEqualTo("새로 수집을 시작한 콘텐츠가 있어요");
		assertThat(items.get(0).metrics()).isNull();
	}

	@Test
	void 브랜드_섹션은_합산_지표를_들고_있다() {
		List<DigestItem> items = assembler.assemble(input(Map.of(),
				List.of(reels("B1", "a", 100L, 10L, 1L), reels("B2", "b", 200L, 20L, 2L)),
				List.of(), List.of(), List.of()));

		DigestItem brand = items.get(0);
		assertThat(brand.type()).isEqualTo("brand_new_posts");
		assertThat(brand.count()).isEqualTo(2);
		assertThat(brand.summary()).isEqualTo("브랜드를 언급한 새 게시물을 찾았어요");
		assertThat(brand.metrics()).isEqualTo(new DigestItem.Metrics(300L, 30L, 3L));
	}

	@Test
	void 피드_게시물의_조회수는_합산에서_제외된다() {
		List<DigestItem> items = assembler.assemble(input(Map.of(),
				List.of(reels("B1", "a", 100L, 10L, 1L), feed("B2", "b", 999L, 20L, 2L)),
				List.of(), List.of(), List.of()));

		assertThat(items.get(0).metrics()).isEqualTo(new DigestItem.Metrics(100L, 30L, 3L));
	}

	@Test
	void 그_주_조회수가_전부_없으면_views는_null이다() {
		List<DigestItem> items = assembler.assemble(input(Map.of(),
				List.of(feed("B1", "a", null, 10L, 1L), feed("B2", "b", null, 20L, 2L)),
				List.of(), List.of(), List.of()));

		assertThat(items.get(0).metrics()).isEqualTo(new DigestItem.Metrics(null, 30L, 3L));
	}

	@Test
	void 수집_종료_항목은_종료분_누적_지표를_들고_있다() {
		List<DigestItem> items = assembler.assemble(input(
				Map.of("collection_ended", 2L),
				List.of(),
				List.of(reels("E1", "a", 70L, 7L, 1L), reels("E2", "b", 30L, 3L, 1L)),
				List.of(), List.of()));

		DigestItem ended = items.get(0);
		assertThat(ended.type()).isEqualTo("collection_ended");
		assertThat(ended.metrics()).isEqualTo(new DigestItem.Metrics(100L, 10L, 2L));
	}

	@Test
	void 캠페인_이름이_있으면_모니터링_진행_문안에_붙는다() {
		List<DigestItem> items = assembler.assemble(input(
				Map.of("collection_started", 1L), List.of(), List.of(), List.of(),
				List.of("여름 캠페인", "가을 캠페인")));

		assertThat(items.get(0).summary())
				.isEqualTo("새로 수집을 시작한 콘텐츠가 있어요 (여름 캠페인, 가을 캠페인)");
	}

	@Test
	void 캠페인_이름이_셋_이상이면_둘만_적고_나머지는_외_N건이다() {
		List<DigestItem> items = assembler.assemble(input(
				Map.of("collection_started", 1L), List.of(), List.of(), List.of(),
				List.of("여름 캠페인", "가을 캠페인", "겨울 캠페인", "봄 캠페인")));

		assertThat(items.get(0).summary())
				.isEqualTo("새로 수집을 시작한 콘텐츠가 있어요 (여름 캠페인, 가을 캠페인 외 2건)");
	}

	@Test
	void 확인필요_섹션_문안과_건수() {
		List<DigestItem> items = assembler.assemble(input(
				Map.of("metrics_private", 2L, "content_issue", 1L),
				List.of(), List.of(), List.of("AD1", "AD2", "AD3"), List.of()));

		assertThat(items).extracting(DigestItem::summary).containsExactly(
				"광고 표기가 없는 등록 게시물이 있어요",
				"게시물을 확인하지 못한 콘텐츠가 있어요",
				"일부 지표가 비공개로 바뀐 콘텐츠가 있어요");
		assertThat(items).extracting(DigestItem::count).containsExactly(3, 1, 2);
	}

	@Test
	void 하이라이트는_조회수_최대_게시물이다() {
		List<DigestItem> items = assembler.assemble(input(Map.of(),
				List.of(reels("B1", "small", 5_000L, 500L, 5L), reels("B2", "big", 123_456L, 10L, 1L)),
				List.of(reels("E1", "mid", 60_000L, 100L, 3L)), List.of(), List.of()));

		DigestItem highlight = items.get(items.size() - 1);
		assertThat(highlight.category()).isEqualTo("highlight");
		assertThat(highlight.type()).isEqualTo("top_post");
		assertThat(highlight.count()).isEqualTo(1);
		assertThat(highlight.summary()).isEqualTo("@big 게시물 · 조회수 12.3만");
	}

	@Test
	void 만_단위가_딱_떨어지면_소수점을_붙이지_않는다() {
		List<DigestItem> items = assembler.assemble(input(Map.of(),
				List.of(reels("B1", "big", 20_000L, 1L, 1L)), List.of(), List.of(), List.of()));

		assertThat(items.get(items.size() - 1).summary()).isEqualTo("@big 게시물 · 조회수 2만");
	}

	@Test
	void 만_미만_조회수는_천단위_구분으로_적는다() {
		List<DigestItem> items = assembler.assemble(input(Map.of(),
				List.of(reels("B1", "big", 9_999L, 1L, 1L)), List.of(), List.of(), List.of()));

		assertThat(items.get(items.size() - 1).summary()).isEqualTo("@big 게시물 · 조회수 9,999");
	}

	@Test
	void 조회수가_전부_없으면_하이라이트는_좋아요_최대로_고른다() {
		List<DigestItem> items = assembler.assemble(input(Map.of(),
				List.of(feed("B1", "few", null, 10L, 1L), feed("B2", "many", null, 88L, 2L)),
				List.of(), List.of(), List.of()));

		DigestItem highlight = items.get(items.size() - 1);
		assertThat(highlight.summary()).isEqualTo("@many 게시물 · 좋아요 88");
		assertThat(highlight.metrics()).isEqualTo(new DigestItem.Metrics(null, 88L, 2L));
	}

	@Test
	void 만_경계값_정확히_10000이면_1만이다() {
		List<DigestItem> items = assembler.assemble(input(Map.of(),
				List.of(reels("B1", "big", 10_000L, 1L, 1L)), List.of(), List.of(), List.of()));

		assertThat(items.get(items.size() - 1).summary()).isEqualTo("@big 게시물 · 조회수 1만");
	}

	@Test
	void 조회수_동률이면_먼저_나온_후보가_선택된다() {
		// Stream.max는 동률일 때 먼저 등장한 원소를 유지한다(BinaryOperator.maxBy 구현) -
		// candidates는 brandNewPosts를 endedPosts보다 앞에 두므로 동률 시 brandNewPosts가 이긴다.
		List<DigestItem> items = assembler.assemble(input(Map.of(),
				List.of(reels("B1", "brand_tied", 1_000L, 1L, 1L)),
				List.of(reels("E1", "ended_tied", 1_000L, 1L, 1L)),
				List.of(), List.of()));

		assertThat(items.get(items.size() - 1).summary()).isEqualTo("@brand_tied 게시물 · 조회수 1,000");
	}

	@Test
	void 좋아요_동률이면_먼저_나온_후보가_선택된다() {
		// 조회수가 전부 없을 때도 같은 규칙 - candidates 순서상 brandNewPosts가 endedPosts보다 앞이다.
		List<DigestItem> items = assembler.assemble(input(Map.of(),
				List.of(feed("B1", "brand_tied", null, 50L, 1L)),
				List.of(feed("E1", "ended_tied", null, 50L, 1L)),
				List.of(), List.of()));

		assertThat(items.get(items.size() - 1).summary()).isEqualTo("@brand_tied 게시물 · 좋아요 50");
	}

	@Test
	void 지표가_하나도_없으면_하이라이트를_만들지_않는다() {
		List<DigestItem> items = assembler.assemble(input(Map.of(),
				List.of(feed("B1", "a", null, null, null)), List.of(), List.of(), List.of()));

		assertThat(items).extracting(DigestItem::type).containsExactly("brand_new_posts");
	}

	@Test
	void 문안에_엠대시가_없다() {
		List<DigestItem> items = assembler.assemble(input(
				Map.of("collection_started", 1L, "collection_ended", 1L, "metrics_private", 1L, "content_issue", 1L),
				List.of(reels("B1", "a", 100L, 10L, 1L)), List.of(reels("E1", "b", 10L, 1L, 1L)),
				List.of("AD1"), List.of("여름 캠페인", "가을 캠페인", "겨울 캠페인")));

		assertThat(items).extracting(DigestItem::summary)
				.allSatisfy(summary -> assertThat(summary).doesNotContain("—"));
	}
}
