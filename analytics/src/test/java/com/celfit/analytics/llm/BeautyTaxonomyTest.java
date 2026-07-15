package com.celfit.analytics.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
}
