package com.celfit.was.v1.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AuthorMaskTest {

	@Test
	void 일반_길이는_앞_2자_뒤_2자를_유지한다() {
		assertThat(AuthorMask.mask("glowdeep_92")).isEqualTo("gl***92");
	}

	@Test
	void 길이_5는_경계_없이_앞뒤_2자씩() {
		assertThat(AuthorMask.mask("abcde")).isEqualTo("ab***de");
	}

	@Test
	void 길이_4는_첫_글자만_유지한다() {
		assertThat(AuthorMask.mask("abcd")).isEqualTo("a***");
	}

	@Test
	void 길이_1은_첫_글자만_유지한다() {
		assertThat(AuthorMask.mask("a")).isEqualTo("a***");
	}

	@Test
	void null은_null을_반환한다() {
		assertThat(AuthorMask.mask(null)).isNull();
	}
}
