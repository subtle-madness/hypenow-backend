package com.celfit.monitoring.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 계정명 해시태그 1종 유도(2026-08-17 축소 — 제외 문자열 폐기와 함께 브랜드명·계정명 루트 후보가
 * 사라지고 계정명 그대로 유도한 태그 1종만 남았다).
 */
class BrandHashtagTagsTest {

	@Test
	void 계정명_그대로_소문자_태그_1종을_유도한다() {
		assertThat(BrandHashtagTags.derive("cclime_official")).containsExactly("cclime_official");
	}

	@Test
	void 대문자_섞인_계정명은_소문자_태그로_유도된다() {
		assertThat(BrandHashtagTags.derive("CClime_Official")).containsExactly("cclime_official");
	}

	@Test
	void 앞뒤_공백은_제거된다() {
		assertThat(BrandHashtagTags.derive("  cclime_official  ")).containsExactly("cclime_official");
	}

	@Test
	void 해시태그_불가_문자에서_잘린다() {
		// IG 해시태그는 점(.)에서 끊긴다 — 점 포함 계정명은 그 앞까지만 태그가 된다.
		assertThat(BrandHashtagTags.derive("cclime.beauty")).containsExactly("cclime");
	}

	@Test
	void 선행_유효_문자가_없으면_빈_집합이다() {
		assertThat(BrandHashtagTags.derive(".beauty")).isEmpty();
	}

	@Test
	void 한글_계정명도_유도된다() {
		assertThat(BrandHashtagTags.derive("끌리메")).containsExactly("끌리메");
	}
}
