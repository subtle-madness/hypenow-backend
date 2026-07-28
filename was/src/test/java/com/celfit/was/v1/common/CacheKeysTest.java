package com.celfit.was.v1.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CacheKeysTest {

	@Test
	void 같은_입력은_같은_해시_다른_입력은_다른_해시() {
		assertThat(CacheKeys.sha256("a=1,b=2")).isEqualTo(CacheKeys.sha256("a=1,b=2"));
		assertThat(CacheKeys.sha256("a=1,b=2")).isNotEqualTo(CacheKeys.sha256("a=1,b=3"));
	}

	@Test
	void hex_64자() {
		assertThat(CacheKeys.sha256("x")).hasSize(64).matches("[0-9a-f]+");
	}
}
