package com.celfit.was.v1.brandmonitoring;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 계정명 해시태그 1종 유도(2026-08-27 태그 장부 갭 수정) — monitoring
 * {@code com.celfit.monitoring.service.BrandHashtagTags}의 규칙 복제본이라 케이스도 같은 것을 고정한다.
 * 두 벌이 갈리면 was 장부와 monitoring 스윕 대상이 어긋나 격리 필터가 조용히 빈 목록을 만든다.
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
