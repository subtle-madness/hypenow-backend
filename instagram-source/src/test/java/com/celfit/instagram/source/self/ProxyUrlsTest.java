package com.celfit.instagram.source.self;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ProxyUrlsTest {

	@Test
	void withCountry_는_username에_cr_국가를_붙인다() {
		assertThat(ProxyUrls.withCountry("http://u:p@h:823", "kr"))
				.isEqualTo("http://u__cr.kr:p@h:823");
	}

	@Test
	void withCountry_는_기존_파라미터블록이_있으면_세미콜론으로_잇는다() {
		assertThat(ProxyUrls.withCountry("http://u__sessid.a:p@h:823", "kr"))
				.isEqualTo("http://u__sessid.a;cr.kr:p@h:823");
	}

	@Test
	void withCountry_는_비밀번호_특수문자와_포트없음도_처리한다() {
		assertThat(ProxyUrls.withCountry("http://user:p@ss@h", "kr"))
				.isEqualTo("http://user__cr.kr:p@ss@h");
	}
}
