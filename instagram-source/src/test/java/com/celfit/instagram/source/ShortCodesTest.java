package com.celfit.instagram.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** 숏코드 ↔ media pk 변환 — 댓글 콜(§10-1)이 pk를 저장 없이 유도하는 근거. */
class ShortCodesTest {

	@Test
	void 릴스_숏코드는_실측_pk와_일치한다() {
		assertThat(ShortCodes.toMediaId("DbV7LgZsKG8")).isEqualTo(3951324523536622012L);
	}

	@Test
	void 알_수_없는_문자는_예외() {
		assertThatThrownBy(() -> ShortCodes.toMediaId("bad!code"))
				.isInstanceOf(IllegalArgumentException.class);
	}
}
