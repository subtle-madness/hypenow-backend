package com.celfit.monitoring.ad;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AdDisclosurePatternsTest {

	@Test
	void 고신뢰_패턴을_찾는다() {
		AdDisclosurePatterns.Match m = AdDisclosurePatterns.findFirstMatch("오늘의 룩 #광고 잘봐주세요");
		assertThat(m).isNotNull();
		assertThat(m.phrase()).isEqualTo("#광고");
		assertThat(m.start()).isEqualTo(6);
	}

	@Test
	void 여러_패턴_중_가장_이른_위치를_고른다() {
		AdDisclosurePatterns.Match m = AdDisclosurePatterns.findFirstMatch("협찬받아 작성했어요 #광고");
		assertThat(m.phrase()).isEqualTo("협찬받");
	}

	@Test
	void 소정의_수수료_원고료_광고료_패턴을_인식한다() {
		assertThat(AdDisclosurePatterns.findFirstMatch("소정의 원고료를 지급받았습니다").phrase())
				.isEqualTo("소정의 원고료");
		assertThat(AdDisclosurePatterns.findFirstMatch("소정의 수수료를 지급받았습니다")).isNotNull();
		assertThat(AdDisclosurePatterns.findFirstMatch("소정의 광고료를 지급받았습니다")).isNotNull();
	}

	@Test
	void 저정밀_단독_광고는_사전에_없다() {
		// "광고판이 예쁘네요" 같은 오탐 방지(스펙 §5 Tier1) — 단독 "광고"는 사전 미등재
		assertThat(AdDisclosurePatterns.findFirstMatch("동네 광고판이 예쁘네요")).isNull();
	}

	@Test
	void 매칭_없으면_null() {
		assertThat(AdDisclosurePatterns.findFirstMatch("오늘의 데일리룩 공유합니다")).isNull();
	}

	@Test
	void 빈_캡션은_null() {
		assertThat(AdDisclosurePatterns.findFirstMatch("")).isNull();
		assertThat(AdDisclosurePatterns.findFirstMatch(null)).isNull();
	}
}
