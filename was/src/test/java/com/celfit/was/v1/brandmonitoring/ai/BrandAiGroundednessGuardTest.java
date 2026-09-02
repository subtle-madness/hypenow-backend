package com.celfit.was.v1.brandmonitoring.ai;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 날조 판정 순수 함수 검증(2026-09-02) - {@link BrandAiAgent}와 분리해 신호 조합별 참/거짓을
 * 결정론으로 고정한다.
 */
class BrandAiGroundednessGuardTest {

	@Test
	void 툴_호출_0회에_표가_있으면_날조로_본다() {
		String answer = "| 계정 | 게시물 | 조회수 | 참여율 |\n| @yoon_yoon_ | 11 | 14520 | 1.82 |";

		assertThat(BrandAiGroundednessGuard.ungrounded(answer, 0, null)).isTrue();
	}

	@Test
	void 툴_호출_0회에_세션_브랜드가_아닌_핸들이_있으면_날조로_본다() {
		String answer = "경쟁사 계정 @other_brand_official 이 최근 활발히 올리고 있어요.";

		assertThat(BrandAiGroundednessGuard.ungrounded(answer, 0, "my_brand")).isTrue();
	}

	@Test
	void 툴_호출_0회여도_세션_브랜드_핸들만_있으면_날조가_아니다() {
		String answer = "질문하신 @my_brand 계정 기준으로 안내드릴게요.";

		assertThat(BrandAiGroundednessGuard.ungrounded(answer, 0, "my_brand")).isFalse();
	}

	@Test
	void 툴_호출_0회여도_표와_핸들이_없으면_날조가_아니다() {
		String answer = "이 어시스턴트는 브랜드 모니터링 데이터에 대해서만 답할 수 있어요.";

		assertThat(BrandAiGroundednessGuard.ungrounded(answer, 0, null)).isFalse();
	}

	@Test
	void 툴_호출이_1회라도_있으면_표가_있어도_날조가_아니다() {
		String answer = "| 계정 | 게시물 | 조회수 | 참여율 |\n| @yoon_yoon_ | 11 | 14520 | 1.82 |";

		assertThat(BrandAiGroundednessGuard.ungrounded(answer, 1, null)).isFalse();
	}
}
