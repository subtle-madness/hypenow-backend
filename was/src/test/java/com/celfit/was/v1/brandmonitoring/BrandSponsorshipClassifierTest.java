package com.celfit.was.v1.brandmonitoring;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 협찬 판정(FE §4.4) 순수 함수 단위 테스트. */
class BrandSponsorshipClassifierTest {

	@Test
	void 판정_규칙_4단계() {
		assertThat(BrandSponsorshipClassifier.classify(true, null)).isEqualTo("sponsored");
		assertThat(BrandSponsorshipClassifier.classify(null, "오늘의 #협찬 후기")).isEqualTo("sponsored");
		assertThat(BrandSponsorshipClassifier.classify(false, "#광고 아님… 이 아니라 광고")).isEqualTo("sponsored"); // 키워드가 플래그 false보다 우선
		assertThat(BrandSponsorshipClassifier.classify(false, "그냥 일상")).isEqualTo("organic");
		assertThat(BrandSponsorshipClassifier.classify(null, "그냥 일상")).isEqualTo("unknown");
		assertThat(BrandSponsorshipClassifier.classify(null, null)).isEqualTo("unknown");
	}

	@Test
	void 한국어_확장_마커_제품제공() {
		assertThat(BrandSponsorshipClassifier.classify(false, "#제품제공 후기입니다")).isEqualTo("sponsored");
		assertThat(BrandSponsorshipClassifier.classify(false, "브랜드에서 제품을 제공받아 작성했어요")).isEqualTo("sponsored");
	}

	@Test
	void 영문_해시태그_마커는_정확한_태그_토큰만() {
		// 운영 실측 — 해외 인플루언서는 #ad류 표기만 쓴다(DZ_SPNbzTKE 등)
		assertThat(BrandSponsorshipClassifier.classify(false, "50% deal ⭐️ #ad \n#cclime")).isEqualTo("sponsored");
		assertThat(BrandSponsorshipClassifier.classify(false, "so good #sponsored")).isEqualTo("sponsored");
		assertThat(BrandSponsorshipClassifier.classify(false, "thanks for the #gifted set")).isEqualTo("sponsored");
		assertThat(BrandSponsorshipClassifier.classify(false, "#PR box unboxing")).isEqualTo("sponsored"); // 대소문자 무시
		// substring이면 #adventure가 #ad에 걸린다 — 태그 전체 일치만 인정
		assertThat(BrandSponsorshipClassifier.classify(false, "our #adventure was #adorable")).isEqualTo("organic");
		assertThat(BrandSponsorshipClassifier.classify(false, "#prospin review")).isEqualTo("organic");
	}

	@Test
	void 외국어_광고_표기() {
		assertThat(BrandSponsorshipClassifier.classify(false, "yeni rutinim 🤍 *reklam")).isEqualTo("sponsored"); // 터키어(DZuy0obyijZ 실측)
		assertThat(BrandSponsorshipClassifier.classify(false, "bu video reklamdır")).isEqualTo("sponsored"); // 교착어 접미사
		assertThat(BrandSponsorshipClassifier.classify(false, "この動画は広告です")).isEqualTo("sponsored");
		assertThat(BrandSponsorshipClassifier.classify(false, "業配影片分享")).isEqualTo("sponsored");
	}
}
