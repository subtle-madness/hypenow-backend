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

	@Test
	void canonical은_null과_리터럴_null을_구분하고_구분자_주입도_구분한다() {
		// 리터럴 문자열 "null"과 실제 null은 다른 튜플이어야 한다(toString() 방식의 캐시 오염 원인).
		assertThat(CacheKeys.canonical("null")).isNotEqualTo(CacheKeys.canonical((Object) null));
		// 길이 접두라 구분자 자체를 값에 주입해도(a;b 하나 vs a,b 둘) 겹치지 않는다.
		assertThat(CacheKeys.canonical("a;b")).isNotEqualTo(CacheKeys.canonical("a", "b"));
	}
}
