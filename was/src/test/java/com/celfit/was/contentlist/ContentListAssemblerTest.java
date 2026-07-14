package com.celfit.was.contentlist;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ContentListAssemblerTest {

	private final ContentListAssembler assembler = new ContentListAssembler(JsonMapper.builder().build());

	// 계획 시드 h1: likes 100·comments 10·views 1000 → ER 0.1100, postedAt 06-28T03:00Z
	private ContentListRow h1() {
		return new ContentListRow(
				"h1", "https://thumb/h1.jpg", "여름 립케어 루틴",
				OffsetDateTime.parse("2026-06-28T03:00:00Z"), "reels",
				"alpha", "알파", "https://pic/alpha.jpg", 5000L,
				1000L, 100L, 10L, 1000L, OffsetDateTime.parse("2026-07-01T03:00:00Z"),
				"sponsored", "[\"립틴트\"]", 2);
	}

	@Test
	void 행을_카드_전체_필드로_조립한다() {
		// reference = 2026-07-03T15:00Z(cutoff) → postedAt 06-28T03:00Z부터 5일 12시간 경과 = 5
		ContentListResponse response = assembler.toResponse(
				3L, List.of(h1()), OffsetDateTime.parse("2026-07-03T15:00:00Z"));

		assertThat(response.totalCount()).isEqualTo(3L);
		ContentListResponse.Item item = response.items().getFirst();
		assertThat(item.shortCode()).isEqualTo("h1");
		assertThat(item.thumbnailUrl()).isEqualTo("https://thumb/h1.jpg");
		assertThat(item.caption()).isEqualTo("여름 립케어 루틴");
		assertThat(item.postedAt()).isEqualTo(OffsetDateTime.parse("2026-06-28T03:00:00Z"));
		assertThat(item.daysSincePosted()).isEqualTo(5L);
		assertThat(item.contentType()).isEqualTo("reels");
		assertThat(item.account().handle()).isEqualTo("alpha");
		assertThat(item.account().displayName()).isEqualTo("알파");
		assertThat(item.account().profileImageUrl()).isEqualTo("https://pic/alpha.jpg");
		assertThat(item.account().followers()).isEqualTo(5000L);
		assertThat(item.views()).isEqualTo(1000L);
		assertThat(item.likes()).isEqualTo(100L);
		assertThat(item.comments()).isEqualTo(10L);
		assertThat(item.engagementRate()).isEqualByComparingTo(new BigDecimal("0.1100"));
		assertThat(item.hypeScore()).isEqualTo(1000L);
		assertThat(item.metricsCapturedAt()).isEqualTo(OffsetDateTime.parse("2026-07-01T03:00:00Z"));
		assertThat(item.adType()).isEqualTo("sponsored");
		assertThat(item.productCategories()).containsExactly("립틴트");
		assertThat(item.brandCount()).isEqualTo(2);
	}

	@Test
	void VLM_null_행은_adType_productCategories_brandCount가_null이다() {
		// 계획 시드 h3: 분석은 있으나 VLM 컬럼 전부 NULL
		ContentListRow h3 = new ContentListRow(
				"h3", "https://thumb/h3.jpg", "데일리 피드 룩",
				OffsetDateTime.parse("2026-07-02T03:00:00Z"), "feed",
				"alpha", "알파", "https://pic/alpha.jpg", 5000L,
				null, 300L, 30L, 330L, OffsetDateTime.parse("2026-07-03T03:00:00Z"),
				null, null, null);

		ContentListResponse response = assembler.toResponse(
				1L, List.of(h3), OffsetDateTime.parse("2026-07-03T15:00:00Z"));

		ContentListResponse.Item item = response.items().getFirst();
		assertThat(item.adType()).isNull();
		assertThat(item.productCategories()).isNull();
		assertThat(item.brandCount()).isNull();
	}

	@Test
	void 피드는_조회수가_없어_참여율이_null이다() {
		ContentListRow feedRow = new ContentListRow(
				"h3", "https://thumb/h3.jpg", "데일리 피드 룩",
				OffsetDateTime.parse("2026-07-02T03:00:00Z"), "feed",
				"alpha", "알파", "https://pic/alpha.jpg", 5000L,
				null, 300L, 30L, 330L, OffsetDateTime.parse("2026-07-03T03:00:00Z"),
				null, null, null);

		ContentListResponse response = assembler.toResponse(
				1L, List.of(feedRow), OffsetDateTime.parse("2026-07-03T15:00:00Z"));

		assertThat(response.items().getFirst().engagementRate()).isNull();
	}
}
