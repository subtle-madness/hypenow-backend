package com.celfit.was.v1.influencer;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.was.v1.influencer.V1InfluencerDiscoveryRepository.BrandRow;
import com.celfit.was.v1.influencer.V1InfluencerDiscoveryRepository.CaptionRow;
import com.celfit.was.v1.influencer.V1InfluencerDiscoveryRepository.CardRow;
import com.celfit.was.v1.influencer.V1InfluencerDiscoveryRepository.EngagementRow;
import com.celfit.was.v1.influencer.V1InfluencerDiscoveryRepository.ShareRow;
import com.celfit.was.v1.influencer.V1InfluencerDiscoveryRepository.ThumbRow;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class V1InfluencerDiscoveryAssemblerTest {

	private final V1InfluencerDiscoveryAssembler assembler = new V1InfluencerDiscoveryAssembler();

	private static CardRow row(String handle) {
		return row(handle, 20000L);
	}

	private static CardRow row(String handle, Long followers) {
		return new CardRow(handle, "이름", "/img/p.jpg", followers, 214L, 380L,
				"소개\n둘째줄", "태그라인", new BigDecimal("12.42"), new BigDecimal("3.84"),
				413200L, 10370L, 152L, 72L, 3L, "contact@glow.co", new BigDecimal("72.5000"), null);
	}

	@Test
	void 카드_변환_스케일과_id_규칙() {
		var cards = assembler.toCards(List.of(row("glow")), List.of(), List.of(), List.of(), List.of(),
				List.of());
		var card = cards.get(0);
		assertThat(card.id()).isEqualTo("glow"); // id = handle (6.4 확정 준용)
		assertThat(card.handle()).isEqualTo("glow");
		assertThat(card.reachMultiplier()).isEqualByComparingTo("12.4"); // 소수 1자리 반올림
		assertThat(card.er()).isEqualByComparingTo("3.8");
		assertThat(card.email()).isEqualTo("contact@glow.co"); // biography 정규식 파싱(V46) 그대로 전달
		assertThat(card.bio()).isEqualTo("소개\n둘째줄"); // 개행 유지
		assertThat(card.followingCount()).isEqualTo(380);
		// hypeScore는 2026-07-30부터 avgHypeScorePrecise(소수)를 그대로 싣는다 — avgHypeScore(정수
		// 72)가 아니라 avgHypeScorePrecise(72.5000)와 일치해야 어셈블러가 실제로 precise 필드를
		// 통과시키는지 잡는다(둘 다 72였다면 이 단언이 무의미했을 것).
		assertThat(card.hypeScore()).isEqualByComparingTo("72.5000");
	}

	@Test
	void bio_tagline_부재는_빈문자열_배열은_빈배열() {
		var bare = new CardRow("mute", "이름", null, 40000L, 40L, 50L, null, null,
				null, null, null, 300L, 10L, null, 0L, null, null, null);
		var card = assembler.toCards(List.of(bare), List.of(), List.of(), List.of(), List.of(),
				List.of()).get(0);
		assertThat(card.bio()).isEmpty();
		assertThat(card.tagline()).isEmpty();
		assertThat(card.effectiveFollowers()).isNull(); // 시계열 없음
		assertThat(card.reachMultiplier()).isNull();
		assertThat(card.collaboratedBrands()).isEmpty();
		assertThat(card.categoryShares()).isEmpty();
		assertThat(card.recentThumbs()).isEmpty();
		assertThat(card.hypeScore()).isNull(); // 점수 가능 콘텐츠 없는 계정
		assertThat(card.email()).isNull(); // biography 매치 없음(또는 NULL)
		assertThat(card.minComments()).isNull(); // 창 표본 0
		assertThat(card.maxComments()).isNull();
		assertThat(card.groupPurchaseCount()).isZero();
		assertThat(card.hasGroupPurchase()).isFalse();
	}

	@Test
	void 비중은_상위_3개까지_썸네일은_KST_날짜() {
		var shares = List.of(
				new ShareRow("glow", "makeup", 40), new ShareRow("glow", "skincare", 30),
				new ShareRow("glow", "cleansing", 20), new ShareRow("glow", "suncare", 10));
		var thumbs = List.of(new ThumbRow("glow", "g1", "/img/t.jpg", "reels", "makeup",
				"sponsored", OffsetDateTime.parse("2026-07-15T16:00:00Z"), 100L, 10L, 1L));
		var card = assembler.toCards(List.of(row("glow")), shares,
				List.of(new BrandRow("glow", "롬앤"), new BrandRow("glow", "클리오")), thumbs,
				List.of(), List.of()).get(0);
		assertThat(card.categoryShares()).hasSize(3);
		assertThat(card.categoryShares().get(0).category()).isEqualTo("makeup");
		assertThat(card.collaboratedBrands()).containsExactly("롬앤", "클리오");
		// UTC 15일 16시 = KST 16일 01시 — 게시일은 KST 달력 날짜
		assertThat(card.recentThumbs().get(0).postedAt()).isEqualTo("2026-07-16");
		assertThat(card.recentThumbs().get(0).contentId()).isEqualTo("g1");
	}

	@Test
	void 본쿼리_정렬_순서를_보존한다() {
		var cards = assembler.toCards(List.of(row("b"), row("a")), List.of(), List.of(), List.of(),
				List.of(), List.of());
		assertThat(cards).extracting(InfluencerCard::handle).containsExactly("b", "a");
	}

	@Test
	void 유효_팔로워는_실반응_산식으로_계산() {
		// followers 10000, 2게시물 (100+10)·(200+20) → EffectiveFollowersTest 기본 케이스와 동일 = 165
		var rows = List.of(row("a", 10_000L));
		var engagements = List.of(
				new EngagementRow("a", null, 100L, 10L),
				new EngagementRow("a", null, 200L, 20L));
		var cards = assembler.toCards(rows, List.of(), List.of(), List.of(), engagements, List.of());
		assertThat(cards.get(0).effectiveFollowers()).isEqualTo(165L);
	}

	@Test
	void 시계열_없는_계정은_유효_팔로워_null() {
		var cards = assembler.toCards(List.of(row("a", 10_000L)),
				List.of(), List.of(), List.of(), List.of(), List.of());
		assertThat(cards.get(0).effectiveFollowers()).isNull();
	}

	@Test
	void 댓글_min_max는_avgComments와_같은_창에서_NULL을_제외하고_계산한다() {
		// comments NULL(댓글 미수집) 1건은 min/max에서 자연 제외 — avg()의 NULL 무시와 동치 계약.
		var rows = List.of(row("a", 10_000L));
		var engagements = List.of(
				new EngagementRow("a", null, 100L, 30L),
				new EngagementRow("a", null, 200L, 10L),
				new EngagementRow("a", null, 50L, null));
		var cards = assembler.toCards(rows, List.of(), List.of(), List.of(), engagements, List.of());
		assertThat(cards.get(0).minComments()).isEqualTo(10);
		assertThat(cards.get(0).maxComments()).isEqualTo(30);
	}

	@Test
	void 댓글_min_max는_시계열_전량이_NULL이면_null() {
		var rows = List.of(row("a", 10_000L));
		var engagements = List.of(new EngagementRow("a", null, 50L, null));
		var cards = assembler.toCards(rows, List.of(), List.of(), List.of(), engagements, List.of());
		assertThat(cards.get(0).minComments()).isNull();
		assertThat(cards.get(0).maxComments()).isNull();
	}

	@Test
	void 공동구매_카운트는_규칙에_매칭되는_캡션만_세고_존재여부는_카운트에서_파생된다() {
		var rows = List.of(row("a", 10_000L));
		var captions = List.of(
				new CaptionRow("a", "이번 공동구매 오픈합니다"),
				new CaptionRow("a", "#공구오픈 링크는 프로필에"),
				new CaptionRow("a", "메이크업 공구 정리했어요")); // # 없는 맨몸 "공구"는 비매칭
		var cards = assembler.toCards(rows, List.of(), List.of(), List.of(), List.of(), captions);
		assertThat(cards.get(0).groupPurchaseCount()).isEqualTo(2);
		assertThat(cards.get(0).hasGroupPurchase()).isTrue();
	}

	@Test
	void 공동구매_캡션이_없으면_0건_false() {
		var rows = List.of(row("a", 10_000L));
		var cards = assembler.toCards(rows, List.of(), List.of(), List.of(), List.of(), List.of());
		assertThat(cards.get(0).groupPurchaseCount()).isZero();
		assertThat(cards.get(0).hasGroupPurchase()).isFalse();
	}
}
