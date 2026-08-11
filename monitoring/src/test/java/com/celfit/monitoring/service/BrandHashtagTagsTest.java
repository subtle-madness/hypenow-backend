package com.celfit.monitoring.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BrandHashtagTagsTest {

	@Test
	void 루트는_상용_접미사를_반복_제거한다() {
		assertThat(BrandHashtagTags.root("cclime_official")).isEqualTo("cclime");
		assertThat(BrandHashtagTags.root("brand.official")).isEqualTo("brand");
		assertThat(BrandHashtagTags.root("brand_official_kr")).isEqualTo("brand");
		assertThat(BrandHashtagTags.root("olens")).isEqualTo("olens");
	}

	@Test
	void 루트가_3자_미만이_되는_제거는_하지_않는다() {
		assertThat(BrandHashtagTags.root("ab_official")).isEqualTo("ab_official");
	}

	@Test
	void 태그셋은_브랜드명_루트_전체계정명_3종이다() {
		assertThat(BrandHashtagTags.derive("끌리메", "cclime_official"))
				.containsExactly("끌리메", "cclime", "cclime_official");
	}

	@Test
	void 브랜드명이_없으면_계정명_유도_2종만이다() {
		assertThat(BrandHashtagTags.derive(null, "cclime_official"))
				.containsExactly("cclime", "cclime_official");
		assertThat(BrandHashtagTags.derive("  ", "cclime_official"))
				.containsExactly("cclime", "cclime_official");
	}

	@Test
	void 브랜드명의_공백과_해시는_제거한다() {
		assertThat(BrandHashtagTags.derive("끌리메 뷰티", "cclime_official"))
				.contains("끌리메뷰티");
		assertThat(BrandHashtagTags.derive("#끌리메", "cclime_official")).contains("끌리메");
	}

	@Test
	void 해시태그_불가_문자를_포함한_후보는_버린다() {
		// IG 해시태그는 점(.)에서 끊긴다 — 점 포함 계정명의 전체계정명 태그는 성립 불가
		assertThat(BrandHashtagTags.derive(null, "cclime.beauty"))
				.containsExactly("cclime");
	}

	@Test
	void 중복_유도는_한_번만_남는다() {
		assertThat(BrandHashtagTags.derive("olens", "olens")).containsExactly("olens");
	}
}
