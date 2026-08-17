package com.celfit.monitoring.ad;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.monitoring.ad.AdDisclosureExtractor.Category;
import com.celfit.monitoring.ad.AdDisclosureExtractor.Disclosure;
import java.util.List;
import org.junit.jupiter.api.Test;

class AdVerdictCombinerTest {

	@Test
	void CLEAR_문구가_적절_위치면_DISCLOSED() {
		String caption = "오늘 룩 소개 #광고";
		AdVerdictResult result = AdVerdictCombiner.combine(caption, false, null,
				List.of(new Disclosure("#광고", Category.CLEAR)));
		assertThat(result.verdict()).isEqualTo("DISCLOSED");
		assertThat(result.source()).isEqualTo("LLM");
		assertThat(result.violations()).isEmpty();
	}

	@Test
	void Tier1_경로_CLEAR가_적절_위치면_DISCLOSED_RULE() {
		String caption = "오늘 룩 소개 #광고";
		int idx = caption.indexOf("#광고");
		var tier1 = new AdDisclosurePatterns.Match("#광고", idx, idx + "#광고".length());
		AdVerdictResult result = AdVerdictCombiner.combine(caption, false, tier1, List.of());
		assertThat(result.verdict()).isEqualTo("DISCLOSED");
		assertThat(result.source()).isEqualTo("RULE");
	}

	@Test
	void CLEAR_있으나_전부_묻힌_위치면_INSUFFICIENT_HIDDEN_PLACEMENT() {
		// 비해시태그 문구를 쓴다 — "#광고"였다면 캡션의 유일한 해시태그라 첫 해시태그 예외로 빠져
		// DISCLOSED가 나온다(이 테스트가 검증하려는 HIDDEN 분기를 태우지 못한다).
		String filler = "가".repeat(250);
		String caption = filler + "광고입니다";
		AdVerdictResult result = AdVerdictCombiner.combine(caption, false, null,
				List.of(new Disclosure("광고입니다", Category.CLEAR)));
		assertThat(result.verdict()).isEqualTo("INSUFFICIENT");
		assertThat(result.violations()).containsExactly("HIDDEN_PLACEMENT");
	}

	@Test
	void AMBIGUOUS만_존재하면_INSUFFICIENT_AMBIGUOUS_EXPRESSION() {
		String caption = "체험단 후기입니다";
		AdVerdictResult result = AdVerdictCombiner.combine(caption, false, null,
				List.of(new Disclosure("체험단", Category.AMBIGUOUS)));
		assertThat(result.verdict()).isEqualTo("INSUFFICIENT");
		assertThat(result.violations()).containsExactly("AMBIGUOUS_EXPRESSION");
	}

	@Test
	void AMBIGUOUS가_묻힌_위치면_묻힘_코드가_병기된다() {
		String filler = "가".repeat(250);
		String caption = filler + "체험단";
		AdVerdictResult result = AdVerdictCombiner.combine(caption, false, null,
				List.of(new Disclosure("체험단", Category.AMBIGUOUS)));
		assertThat(result.violations()).containsExactlyInAnyOrder("AMBIGUOUS_EXPRESSION", "HIDDEN_PLACEMENT");
	}

	@Test
	void FOREIGN만_존재하면_INSUFFICIENT_FOREIGN_LANGUAGE() {
		String caption = "today's look Sponsor";
		AdVerdictResult result = AdVerdictCombiner.combine(caption, false, null,
				List.of(new Disclosure("Sponsor", Category.FOREIGN)));
		assertThat(result.verdict()).isEqualTo("INSUFFICIENT");
		assertThat(result.violations()).containsExactly("FOREIGN_LANGUAGE");
	}

	@Test
	void 대소문자가_다른_phrase는_환각_차단으로_폐기된다() {
		// 캡션엔 "Sponsor"(대문자 S)뿐인데 LLM이 소문자 "sponsor"를 인용 — exact substring 대조
		// 원칙대로 대소문자 불일치도 캡션에 "실존하지 않음"으로 취급해 폐기한다.
		String caption = "today's look Sponsor";
		AdVerdictResult result = AdVerdictCombiner.combine(caption, false, null,
				List.of(new Disclosure("sponsor", Category.FOREIGN)));
		assertThat(result.verdict()).isEqualTo("NOT_DISCLOSED");
		assertThat(result.evidence()).isEmpty();
		assertThat(result.discardedPhrases()).containsExactly("sponsor");
	}

	@Test
	void 빈_phrase는_즉시_폐기된다() {
		AdVerdictResult result = AdVerdictCombiner.combine("오늘의 데일리룩", false, null,
				List.of(new Disclosure("   ", Category.CLEAR)));
		assertThat(result.verdict()).isEqualTo("NOT_DISCLOSED");
		assertThat(result.evidence()).isEmpty();
		assertThat(result.discardedPhrases()).containsExactly("   ");
	}

	@Test
	void UNCERTAIN_문구뿐이면_UNCERTAIN_위반_없음() {
		String caption = "협업 관련 문의는 DM으로";
		AdVerdictResult result = AdVerdictCombiner.combine(caption, false, null,
				List.of(new Disclosure("협업", Category.UNCERTAIN)));
		assertThat(result.verdict()).isEqualTo("UNCERTAIN");
		assertThat(result.violations()).isEmpty();
	}

	@Test
	void 문구_없음_사진은_NOT_DISCLOSED() {
		AdVerdictResult result = AdVerdictCombiner.combine("오늘의 데일리룩", false, null, List.of());
		assertThat(result.verdict()).isEqualTo("NOT_DISCLOSED");
		assertThat(result.source()).isEqualTo("RULE");
		assertThat(result.violations()).containsExactly("NO_DISCLOSURE");
	}

	@Test
	void 문구_없음_릴스는_UNCERTAIN() {
		AdVerdictResult result = AdVerdictCombiner.combine("오늘의 데일리룩", true, null, List.of());
		assertThat(result.verdict()).isEqualTo("UNCERTAIN");
		assertThat(result.violations()).isEmpty();
	}

	@Test
	void LLM이_인용한_문구가_캡션에_없으면_환각_차단_폐기() {
		// "#광고"를 인용했지만 실제 캡션엔 없다 — substring 대조 실패, 폐기 후 문구 없음으로 처리
		AdVerdictResult result = AdVerdictCombiner.combine("오늘의 데일리룩", false, null,
				List.of(new Disclosure("#광고", Category.CLEAR)));
		assertThat(result.verdict()).isEqualTo("NOT_DISCLOSED");
		assertThat(result.evidence()).isEmpty();
		assertThat(result.discardedPhrases()).containsExactly("#광고");
	}

	@Test
	void Tier1_매칭이_묻힌_위치여도_evidence로_넘어온다() {
		// "광고입니다"는 AdDisclosurePatterns의 Tier1 사전 항목(해시태그 아님) — "#광고"를 쓰면
		// 캡션의 유일한 해시태그라 첫 해시태그 예외로 빠져 HIDDEN을 검증할 수 없다.
		String filler = "가".repeat(250);
		String caption = filler + "광고입니다";
		var tier1 = new AdDisclosurePatterns.Match("광고입니다", filler.length(),
				filler.length() + "광고입니다".length());
		AdVerdictResult result = AdVerdictCombiner.combine(caption, false, tier1, List.of());
		assertThat(result.verdict()).isEqualTo("INSUFFICIENT");
		assertThat(result.evidence()).extracting(AdVerdictResult.Evidence::phrase).containsExactly("광고입니다");
	}

	@Test
	void 여러_카테고리가_섞이면_CLEAR_적절_위치가_우선한다() {
		String caption = "체험단이지만 #광고 표기도 했어요";
		AdVerdictResult result = AdVerdictCombiner.combine(caption, false, null,
				List.of(new Disclosure("체험단", Category.AMBIGUOUS), new Disclosure("#광고", Category.CLEAR)));
		assertThat(result.verdict()).isEqualTo("DISCLOSED");
	}
}
