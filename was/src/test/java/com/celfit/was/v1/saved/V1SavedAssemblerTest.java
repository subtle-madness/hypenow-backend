package com.celfit.was.v1.saved;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.was.v1.content.ContentCard;
import com.celfit.was.v1.saved.V1SavedAssembler.ContentItem;
import com.celfit.was.v1.saved.V1SavedAssembler.InfluencerItem;
import com.celfit.was.v1.saved.V1SavedRepository.SavedContentRow;
import com.celfit.was.v1.saved.V1SavedRepository.SavedInfluencerRow;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 조합기 순수 로직 — memo 정규화·저장순 유지·미러 부재 정책(콘텐츠 제외 vs 인플루언서 handle-only). */
class V1SavedAssemblerTest {

	private final V1SavedAssembler assembler = new V1SavedAssembler();

	@Test
	void memo_정규화는_null_빈문자열_공백뿐이면_null이고_그외는_다듬는다() {
		assertThat(V1SavedAssembler.normalizeMemo(null)).isNull();
		assertThat(V1SavedAssembler.normalizeMemo("")).isNull();
		assertThat(V1SavedAssembler.normalizeMemo("   ")).isNull();
		assertThat(V1SavedAssembler.normalizeMemo("\t\n ")).isNull();
		assertThat(V1SavedAssembler.normalizeMemo("  협업 후보  ")).isEqualTo("협업 후보");
		assertThat(V1SavedAssembler.normalizeMemo("메모")).isEqualTo("메모");
	}

	@Test
	void savedAt은_ISO_8601_UTC로_찍는다() {
		assertThat(assembler.isoZ(OffsetDateTime.parse("2026-07-15T09:00:00+09:00")))
				.isEqualTo("2026-07-15T00:00:00Z");
		assertThat(assembler.isoZ(null)).isNull();
	}

	@Test
	void 콘텐츠_조합은_저장순을_유지하고_미러에_없는_코드는_제외한다() {
		OffsetDateTime t = OffsetDateTime.parse("2026-07-15T00:00:00Z");
		List<SavedContentRow> saved = List.of(
				new SavedContentRow("c1", "메모1", t),
				new SavedContentRow("c2", null, t), // 미러 부재 → 제외
				new SavedContentRow("c3", "메모3", t));
		Map<String, ContentCard> cards = Map.of("c1", card("c1"), "c3", card("c3"));

		List<ContentItem> items = assembler.toContentItems(saved, cards);

		assertThat(items).extracting(i -> i.content().id()).containsExactly("c1", "c3");
		assertThat(items.get(0).memo()).isEqualTo("메모1");
		assertThat(items.get(0).savedAt()).isEqualTo("2026-07-15T00:00:00Z");
	}

	@Test
	void 인플루언서_조합은_미러_부재_시_제외하지_않고_handle만_채운다() {
		OffsetDateTime t = OffsetDateTime.parse("2026-07-15T00:00:00Z");
		List<SavedInfluencerRow> saved = List.of(
				new SavedInfluencerRow("alpha", "1순위", t),
				new SavedInfluencerRow("ghost", null, t)); // accounts에 없음
		Map<String, ContentCard.Influencer> profiles = Map.of(
				"alpha", new ContentCard.Influencer("alpha", "alpha", "알파", "https://pic/a.jpg", 5000L));

		List<InfluencerItem> items = assembler.toInfluencerItems(saved, profiles);

		assertThat(items).hasSize(2);
		assertThat(items.get(0).influencer().displayName()).isEqualTo("알파");
		assertThat(items.get(0).memo()).isEqualTo("1순위");
		// 부재 인플루언서: handle만, 나머지 null
		assertThat(items.get(1).influencer().id()).isEqualTo("ghost");
		assertThat(items.get(1).influencer().handle()).isEqualTo("ghost");
		assertThat(items.get(1).influencer().displayName()).isNull();
		assertThat(items.get(1).influencer().followers()).isNull();
	}

	private static ContentCard card(String id) {
		return new ContentCard(id, null, null, null, null, null, List.of(), null, null, null, null,
				null, null, null, null, List.of(), List.of(), List.of(), null, null);
	}
}
