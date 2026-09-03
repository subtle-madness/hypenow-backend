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

	// ---------- 답글 선행 @핸들(2026-09-03, FE 피드백 09-01 #4-D) ----------

	@Test
	void 답글_선행_멘션은_작성자와_같은_규칙으로_가린다() {
		assertThat(AuthorMask.maskReply("@nunu.zip_ 감사합니다!")).isEqualTo("@nu***p_ 감사합니다!");
	}

	@Test
	void 답글_선행_멘션이_없으면_그대로다() {
		assertThat(AuthorMask.maskReply("감사합니다 @nunu.zip_ 님")).isEqualTo("감사합니다 @nunu.zip_ 님");
		assertThat(AuthorMask.maskReply("@ 공백뒤")).isEqualTo("@ 공백뒤");
	}

	@Test
	void 답글_연속_선행_멘션은_전부_가리고_중간_멘션은_두다() {
		assertThat(AuthorMask.maskReply("@glowdeep_92 @abcd 고마워요 @keep.me"))
				.isEqualTo("@gl***92 @a*** 고마워요 @keep.me");
	}

	@Test
	void 답글_멘션만_있어도_가린다() {
		assertThat(AuthorMask.maskReply("@glowdeep_92")).isEqualTo("@gl***92");
		assertThat(AuthorMask.maskReply("@glowdeep_92,감사")).isEqualTo("@gl***92,감사");
	}

	@Test
	void 답글_null은_null이다() {
		assertThat(AuthorMask.maskReply(null)).isNull();
	}
}
