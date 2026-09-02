package com.celfit.was.v1.brandmonitoring;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 캡션 해시태그 추출 규칙(스펙 2026-08-31 §3) — 인스타 링크화(#[\p{L}\p{N}_]+)와의 정합이 계약이다.
 */
class BrandCaptionHashtagsTest {

	@Test
	void 한글_영문_숫자_언더스코어_태그를_등장_순서대로_추출한다() {
		assertThat(BrandCaptionHashtags.extract("올영세일 시작! #올영세일 #OliveYoung #2026_pick 갑니다"))
				.containsExactly("올영세일", "OliveYoung", "2026_pick");
	}

	@Test
	void 정규화_키가_같은_태그는_첫_등장_표기_하나로_dedup한다() {
		assertThat(BrandCaptionHashtags.extract("#OliveYoung #oliveyoung #OLIVEYOUNG"))
				.containsExactly("OliveYoung");
	}

	@Test
	void 숫자만인_태그도_추출한다() {
		// 인스타는 숫자-only 태그도 링크화한다(스펙 검증 항목).
		assertThat(BrandCaptionHashtags.extract("새해 #2026 목표")).containsExactly("2026");
	}

	@Test
	void 캡션_중간에_붙은_태그도_추출한다() {
		// 인스타 파싱은 # 앞 문자를 제약하지 않는다 — "가나다#세일"의 #세일도 링크화된다.
		assertThat(BrandCaptionHashtags.extract("가나다#세일")).containsExactly("세일");
	}

	@Test
	void 연속_샵은_뒤의_유효_태그만_잡는다() {
		assertThat(BrandCaptionHashtags.extract("##세일")).containsExactly("세일");
	}

	@Test
	void 구두점과_공백에서_태그가_끝난다() {
		assertThat(BrandCaptionHashtags.extract("#세일! 그리고 #할인, 끝")).containsExactly("세일", "할인");
	}

	@Test
	void 전각_샵은_추출하지_않는다() {
		// 인스타가 전각 ＃(U+FF03)를 링크화하지 않음(스펙 검증 항목). 광고 표기 판정이 ＃를
		// 포함하는 것과 다른 이유는 목적이 달라서다 — 스펙 §3.
		assertThat(BrandCaptionHashtags.extract("＃세일 이벤트")).isEmpty();
	}

	@Test
	void 이모지는_태그를_끊는다() {
		// 알려진 갭(수용): 인스타는 #세일❤️을 한 태그로 링크화하지만 우리는 #세일로 자른다.
		assertThat(BrandCaptionHashtags.extract("#세일❤️ #❤️")).containsExactly("세일");
	}

	@Test
	void null과_빈_캡션은_빈_목록이다() {
		assertThat(BrandCaptionHashtags.extract(null)).isEmpty();
		assertThat(BrandCaptionHashtags.extract("")).isEmpty();
		assertThat(BrandCaptionHashtags.extract("태그 없는 캡션")).isEmpty();
	}

	@Test
	void 정규화는_ROOT_로케일_소문자다() {
		assertThat(BrandCaptionHashtags.normalize("OliveYoung")).isEqualTo("oliveyoung");
		assertThat(BrandCaptionHashtags.normalize("세일")).isEqualTo("세일");
	}
}
