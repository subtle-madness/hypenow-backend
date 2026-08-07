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
}
