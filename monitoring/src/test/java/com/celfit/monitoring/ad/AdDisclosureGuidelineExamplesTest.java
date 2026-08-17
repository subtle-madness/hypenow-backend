package com.celfit.monitoring.ad;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.monitoring.ad.AdDisclosureExtractor.Category;
import com.celfit.monitoring.ad.AdDisclosureExtractor.Disclosure;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 공정위예규 제499호 Ⅴ.6 예시(스펙 §2) 전수 고정 — LLM 무관, Tier1(적절 예)·Tier3 조합표(부적절
 * 카테고리)만으로 검증한다. 지침이 명시한 사례가 틀리면 이 테스트가 빌드를 깬다.
 */
class AdDisclosureGuidelineExamplesTest {

	// ---------- 적절 예(Tier1 사전 매칭 대상, 앞부분 배치로 VISIBLE 위치) ----------

	@Test
	void 적절_예_광고() {
		assertDisclosed("오늘 소개할 제품 #광고");
	}

	@Test
	void 적절_예_협찬() {
		assertDisclosed("#협찬 받았어요 오늘의 룩");
	}

	@Test
	void 적절_예_대가성_광고() {
		assertDisclosed("대가성 광고 포함된 게시물입니다");
	}

	@Test
	void 적절_예_금전적_지원() {
		// "금전적 지원"은 Tier1 사전에 없다(오탐 여지 있는 표현) — LLM CLEAR 추출을 가정해 Tier3만 검증
		AdVerdictResult result = AdVerdictCombiner.combine("금전적 지원을 받아 작성했습니다", false, null,
				List.of(new Disclosure("금전적 지원을 받아", Category.CLEAR)));
		assertThat(result.verdict()).isEqualTo("DISCLOSED");
	}

	@Test
	void 적절_예_무료_상품_상품_협찬_상품_할인() {
		for (String phrase : List.of("무료 상품", "상품 협찬", "상품 할인")) {
			AdVerdictResult result = AdVerdictCombiner.combine(phrase + " 받아 작성했습니다", false, null,
					List.of(new Disclosure(phrase, Category.CLEAR)));
			assertThat(result.verdict()).as(phrase).isEqualTo("DISCLOSED");
		}
	}

	// ---------- 부적절 예(전부 AMBIGUOUS로 LLM이 분류한다고 가정 — Tier3만 검증) ----------

	@Test
	void 부적절_예_체험_후기_체험단_선물_보내주셨어요() {
		for (String phrase : List.of("체험 후기", "체험단", "선물", "에서 보내주셨어요")) {
			AdVerdictResult result = AdVerdictCombiner.combine(phrase + " 잘 썼어요", false, null,
					List.of(new Disclosure(phrase, Category.AMBIGUOUS)));
			assertThat(result.verdict()).as(phrase).isEqualTo("INSUFFICIENT");
			assertThat(result.violations()).as(phrase).contains("AMBIGUOUS_EXPRESSION");
		}
	}

	@Test
	void 부적절_예_브랜드해시태그_단순언급() {
		AdVerdictResult result = AdVerdictCombiner.combine("#쿨더마 다녀왔어요", false, null,
				List.of(new Disclosure("#쿨더마", Category.AMBIGUOUS)));
		assertThat(result.verdict()).isEqualTo("INSUFFICIENT");
	}

	@Test
	void 부적절_예_브랜드명_계정명_콜라보표기() {
		AdVerdictResult result = AdVerdictCombiner.combine("쿨더마×나의계정 콜라보", false, null,
				List.of(new Disclosure("쿨더마×나의계정", Category.AMBIGUOUS)));
		assertThat(result.verdict()).isEqualTo("INSUFFICIENT");
	}

	@Test
	void 부적절_예_이해하기_어려운_줄임말() {
		// 스펙 §2(라인 28-29)가 "이해하기 어려운 줄임말"을 부적절 예로 명시하지만, 계획 Task 6의 LLM
		// 프롬프트(AMBIGUOUS 분류 예시 목록)에도 구체 줄임말 표현은 없다(grep 확인, "줄임말" 2건 —
		// 둘 다 일반 서술뿐). 프롬프트에 구체 예시가 없어 "유광"(유료광고의 줄임말)을 직접 골라 쓴다.
		AdVerdictResult result = AdVerdictCombiner.combine("유광 표기합니다", false, null,
				List.of(new Disclosure("유광", Category.AMBIGUOUS)));
		assertThat(result.verdict()).isEqualTo("INSUFFICIENT");
		assertThat(result.violations()).contains("AMBIGUOUS_EXPRESSION");
	}

	@Test
	void 부적절_예_외국어_단독_AD_PR_Sponsor_spon_sp_Collabo_앰버서더_땡스투() {
		for (String phrase : List.of("AD", "PR", "Sponsor", "spon", "sp", "Collabo", "앰버서더", "땡스 투")) {
			AdVerdictResult result = AdVerdictCombiner.combine("오늘의 룩 " + phrase, false, null,
					List.of(new Disclosure(phrase, Category.FOREIGN)));
			assertThat(result.verdict()).as(phrase).isEqualTo("INSUFFICIENT");
			assertThat(result.violations()).as(phrase).containsExactly("FOREIGN_LANGUAGE");
		}
	}

	@Test
	void 부적절_예_본문_중간_구분없이_삽입은_HIDDEN() {
		// 비해시태그 문구("광고입니다", Tier1 사전 항목) — "#광고"였다면 캡션의 유일한 해시태그라
		// 첫 해시태그 예외(지침 다.(2)③)로 빠져 DISCLOSED가 나온다. 이 테스트는 "본문 중간 삽입"
		// 부적절 예시(지침 다.(2)①)를 검증하는 것이라 해시태그 예외 경로를 밟으면 안 된다.
		String filler = "가".repeat(250);
		String caption = filler + "광고입니다";
		AdVerdictResult result = AdVerdictCombiner.combine(caption, false, null,
				List.of(new Disclosure("광고입니다", Category.CLEAR)));
		assertThat(result.verdict()).isEqualTo("INSUFFICIENT");
		assertThat(result.violations()).containsExactly("HIDDEN_PLACEMENT");
	}

	@Test
	void 부적절_예_여러_해시태그_사이에_묻힘() {
		// 첫 해시태그가 아니고, 뒤쪽(접힘 하한 밖)에 있으면 묻힘 — 첫 해시태그 예외(다.(2)③)와 구분
		String manyTags = "#a #b #c #d #e #f #g #h #i #j #k #l #m #n #o #p #q #r #s #t ".repeat(6);
		String caption = manyTags + "#광고";
		AdVerdictResult result = AdVerdictCombiner.combine(caption, false, null,
				List.of(new Disclosure("#광고", Category.CLEAR)));
		assertThat(result.verdict()).isEqualTo("INSUFFICIENT");
		assertThat(result.violations()).containsExactly("HIDDEN_PLACEMENT");
	}

	private static void assertDisclosed(String caption) {
		AdDisclosurePatterns.Match tier1 = AdDisclosurePatterns.findFirstMatch(caption);
		assertThat(tier1).as("Tier1 매칭: " + caption).isNotNull();
		AdVerdictResult result = AdVerdictCombiner.combine(caption, false, tier1, List.of());
		assertThat(result.verdict()).as(caption).isEqualTo("DISCLOSED");
	}
}
