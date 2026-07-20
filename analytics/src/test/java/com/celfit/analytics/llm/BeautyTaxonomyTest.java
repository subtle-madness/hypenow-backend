package com.celfit.analytics.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 어휘 스냅샷 인스턴스의 조립 동작 단위 테스트 (소형 픽스처).
 * 실제 시드(V30)가 프론트 배포본과 일치하는지는 BeautyTaxonomySeedTest가 검증한다.
 */
class BeautyTaxonomyTest {

	private static final BeautyTaxonomy FIXTURE = new BeautyTaxonomy(List.of(
			new BeautyTaxonomy.Entry("skincare", "스킨케어", "스킨/토너", "스킨"),
			new BeautyTaxonomy.Entry("skincare", "스킨케어", "스킨/토너", "토너"),
			new BeautyTaxonomy.Entry("makeup", "메이크업", "립메이크업", "립틴트"),
			new BeautyTaxonomy.Entry("makeup", "메이크업", "립메이크업", "립스틱")),
			List.of("올리브영", "다이소"));

	@Test
	void 대분류_slug_집합을_행에서_조립한다() {
		assertEquals(Set.of("skincare", "makeup"), FIXTURE.mainCategories());
	}

	@Test
	void 중분류와_소분류_라벨을_모두_포함하는_집합을_제공한다() {
		Set<String> labels = FIXTURE.allMidAndSubLabels();

		// 프론트 mid/sub 필터가 sub_categories 배열 포함 여부로 매칭 — 중분류·소분류 라벨 둘 다 어휘다
		assertTrue(labels.contains("립메이크업")); // 중분류
		assertTrue(labels.contains("립틴트"));     // 소분류
		assertTrue(labels.contains("스킨/토너"));
	}

	@Test
	void 소분류_라벨_집합은_중분류를_포함하지_않는다() {
		Set<String> subs = FIXTURE.allSubLabels();

		assertTrue(subs.contains("립틴트"));
		assertFalse(subs.contains("립메이크업")); // 중분류는 카드 칩(제품 카테고리) 어휘가 아니다
	}

	@Test
	void 유통사_집합과_프롬프트_나열을_제공한다() {
		assertEquals(Set.of("올리브영", "다이소"), FIXTURE.distributors());
		assertEquals("올리브영|다이소", FIXTURE.distributorsPrompt()); // 시드 정렬 순서 유지
	}

	@Test
	void 프롬프트_분류표는_slug와_라벨_계층을_행_순서대로_렌더링한다() {
		assertEquals("""
				skincare(스킨케어): 스킨/토너[스킨, 토너]
				makeup(메이크업): 립메이크업[립틴트, 립스틱]""",
				FIXTURE.promptTable());
	}

	@Test
	void 단일_소분류_라벨은_해당_대분류로_역유도된다() {
		assertEquals("makeup", FIXTURE.deriveMain(List.of("립틴트")));
	}

	@Test
	void 중분류_라벨도_대분류로_역유도된다() {
		assertEquals("makeup", FIXTURE.deriveMain(List.of("립메이크업")));
	}

	@Test
	void 최다_득표_대분류로_역유도된다() {
		// makeup 2표(립틴트·립스틱) > skincare 1표(스킨)
		assertEquals("makeup", FIXTURE.deriveMain(List.of("스킨", "립틴트", "립스틱")));
	}

	@Test
	void 동점이면_분류표_앞선_대분류로_결정론적_tie_break한다() {
		// skincare 1표·makeup 1표 동점 → 픽스처 순서상 skincare가 앞(main_order 개념)
		assertEquals("skincare", FIXTURE.deriveMain(List.of("스킨", "립틴트")));
	}

	@Test
	void 어휘_밖_라벨만_있으면_null이다() {
		assertNull(FIXTURE.deriveMain(List.of("없는라벨")));
		assertNull(FIXTURE.deriveMain(List.of()));
		assertNull(FIXTURE.deriveMain(null));
	}

	@Test
	void 여러_대분류에_걸치는_애매한_라벨은_투표에서_제외된다() {
		// "겸용"이 skincare·makeup 둘 다에 속하면 단일 대분류로 못 정하므로 집계 제외 → 남은 단일표로 결정
		BeautyTaxonomy ambiguous = new BeautyTaxonomy(List.of(
				new BeautyTaxonomy.Entry("skincare", "스킨케어", "겸용", "겸용"),
				new BeautyTaxonomy.Entry("makeup", "메이크업", "겸용", "겸용"),
				new BeautyTaxonomy.Entry("makeup", "메이크업", "립메이크업", "립틴트")),
				List.of("올리브영"));
		assertEquals("makeup", ambiguous.deriveMain(List.of("겸용", "립틴트")));
		assertNull(ambiguous.deriveMain(List.of("겸용"))); // 애매 라벨만 → 결정 불가
	}

	@Test
	void 리스트에_null_원소가_섞여도_안전하게_무시한다() {
		assertEquals("makeup", FIXTURE.deriveMain(java.util.Arrays.asList("립틴트", null)));
	}
}
