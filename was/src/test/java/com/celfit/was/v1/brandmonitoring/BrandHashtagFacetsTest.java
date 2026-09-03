package com.celfit.was.v1.brandmonitoring;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class BrandHashtagFacetsTest {

	private static BrandPostAssembler.PostRef ref(String shortcode, String... hashtags) {
		return new BrandPostAssembler.PostRef(shortcode, null, null, null, null, null, null,
				null, null, null, null, null, List.of(hashtags), null);
	}

	@Test
	void 게시물_수_내림차순으로_집계하고_동수는_키_사전순이다() {
		List<BrandHashtagFacets.Entry> facet = BrandHashtagFacets.of(List.of(
				ref("a", "세일", "올영"), ref("b", "세일"), ref("c", "할인")));
		assertThat(facet).containsExactly(
				new BrandHashtagFacets.Entry("세일", 2),
				new BrandHashtagFacets.Entry("올영", 1),
				new BrandHashtagFacets.Entry("할인", 1));
	}

	@Test
	void 대소문자_변형은_한_키로_합산하고_최빈_원문_표기를_쓴다() {
		// a·b가 "OliveYoung", c가 "oliveyoung" — 최빈 표기 OliveYoung으로 노출.
		List<BrandHashtagFacets.Entry> facet = BrandHashtagFacets.of(List.of(
				ref("a", "OliveYoung"), ref("b", "OliveYoung"), ref("c", "oliveyoung")));
		assertThat(facet).containsExactly(new BrandHashtagFacets.Entry("OliveYoung", 3));
	}

	@Test
	void 필터_판정은_정규화_키로_대소문자_무시_매칭한다() {
		BrandPostAssembler.PostRef r = ref("a", "OliveYoung", "세일");
		assertThat(BrandHashtagFacets.matches(r, "oliveyoung")).isTrue();
		assertThat(BrandHashtagFacets.matches(r, "세일")).isTrue();
		assertThat(BrandHashtagFacets.matches(r, "할인")).isFalse();
	}

	@Test
	void 필터_키_파싱은_앞의_샵을_떼고_정규화하며_빈_값은_null이다() {
		assertThat(BrandHashtagFacets.filterKey("#OliveYoung")).isEqualTo("oliveyoung");
		assertThat(BrandHashtagFacets.filterKey("세일")).isEqualTo("세일");
		assertThat(BrandHashtagFacets.filterKey("  ")).isNull();
		assertThat(BrandHashtagFacets.filterKey(null)).isNull();
		assertThat(BrandHashtagFacets.filterKey("#")).isNull();
	}

	@Test
	void 태그_없는_게시물만_있으면_빈_facet이다() {
		assertThat(BrandHashtagFacets.of(List.of(ref("a")))).isEmpty();
	}
}
