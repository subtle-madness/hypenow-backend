package com.celfit.was.v1.content;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ContentCardAssemblerTest {

	private final ContentCardAssembler assembler = new ContentCardAssembler(new ObjectMapper());

	private ContentCardRow row() {
		return new ContentCardRow("SC1", "https://thumb/1.jpg", "다이소 찐템",
				OffsetDateTime.parse("2025-12-20T20:30:00Z"), // KST 2025-12-21 05:30
				"reels", BigDecimal.valueOf(25), "https://www.instagram.com/reel/SC1/",
				3307180L, 42216L, 86L, 62L, OffsetDateTime.parse("2026-07-09T05:46:00Z"),
				"makeup", "[\"아이라이너\"]", "organic",
				"[{\"name\":\"머지\",\"evidence\":\"e\"}]",
				"[{\"name\":\"삼각형 웨지 퍼프\",\"brand\":\"머지\"}]",
				"[\"다이소\"]",
				"zingdong__", "현징이", "/avatars/z.jpg", 33325L, new BigDecimal("62.4321"));
	}

	@Test
	void 스펙_5_3_카드로_조립한다() {
		ContentCard card = assembler.toCard(row());
		assertThat(card.id()).isEqualTo("SC1");
		assertThat(card.postedAt()).isEqualTo("2025-12-21"); // KST 달력 날짜 (스펙 3.4)
		assertThat(card.updatedAt()).isEqualTo("2026-07-09T05:46:00Z");
		assertThat(card.brands()).containsExactly("머지");        // 이름 배열로 평탄화
		assertThat(card.products()).containsExactly("삼각형 웨지 퍼프");
		assertThat(card.distributors()).containsExactly("다이소");
		assertThat(card.subCategories()).containsExactly("아이라이너");
		assertThat(card.influencer().handle()).isEqualTo("zingdong__");
		// hypeScore는 2026-07-30부터 hypeScorePrecise(소수, 출력 매핑 반영)를 그대로 싣는다 —
		// hype_score(정수, 62)가 아니라 hype_score_precise(62.4321)와 일치해야 한다(스펙 §10).
		assertThat(card.hypeScore()).isEqualByComparingTo("62.4321");
	}

	@Test
	void jsonb_null은_빈_배열로_내린다() {
		ContentCardRow r = new ContentCardRow("SC2", null, "c", OffsetDateTime.now(), "feed",
				null, "u", null, 10L, 2L, 30L, OffsetDateTime.now(),
				"skincare", null, "organic", null, null, null, "h", "d", "p", 100L, null);
		ContentCard card = assembler.toCard(r);
		assertThat(card.brands()).isEmpty();
		assertThat(card.products()).isEmpty();
		assertThat(card.distributors()).isEmpty();
		assertThat(card.subCategories()).isEmpty();
		assertThat(card.views()).isNull(); // 피드 views null 규약 (스펙 3.6)
		assertThat(card.hypeScore()).isNull();
	}
}
