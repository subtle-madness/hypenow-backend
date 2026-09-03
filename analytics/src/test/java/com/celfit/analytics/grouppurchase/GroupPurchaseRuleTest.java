package com.celfit.analytics.grouppurchase;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.celfit.analytics.grouppurchase.GroupPurchaseRule.Verdict;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

/** 공동구매 규칙 표(스펙 §3) 전 행 + 도구 어휘 각 1건 + 골드 사례 검증. */
class GroupPurchaseRuleTest {

	@Test
	void 공동구매_포함이면_도구_어휘와_무관하게_확정_참() {
		assertEquals(Verdict.CONFIRMED_TRUE, GroupPurchaseRule.evaluate("공동구매 전동 드릴").verdict());
	}

	@Test
	void 해시태그_공구는_애매_분류보다_먼저_확정_참() {
		assertEquals(Verdict.CONFIRMED_TRUE, GroupPurchaseRule.evaluate("#공구 드릴 소개").verdict());
	}

	@Test
	void 공구만_있고_도구_어휘가_없으면_확정_참() {
		assertEquals(Verdict.CONFIRMED_TRUE, GroupPurchaseRule.evaluate("이번주 공구 오픈합니다").verdict());
	}

	@Test
	void 공구와_도구_어휘가_같이_있으면_애매() {
		assertEquals(Verdict.AMBIGUOUS, GroupPurchaseRule.evaluate("공구 없이 조립했어요").verdict());
	}

	@Test
	void 드릴게요_류_오분류는_애매로_무해하게_흡수된다() {
		assertEquals(Verdict.AMBIGUOUS, GroupPurchaseRule.evaluate("드릴게요 공구 오픈").verdict());
	}

	@Test
	void 공구함처럼_어휘_자체에_공구가_포함돼도_애매() {
		assertEquals(Verdict.AMBIGUOUS, GroupPurchaseRule.evaluate("공구함 정리").verdict());
	}

	@Test
	void 공구도_공동구매도_없으면_확정_거짓() {
		assertEquals(Verdict.CONFIRMED_FALSE, GroupPurchaseRule.evaluate("오늘의 데일리룩 소개").verdict());
	}

	@Test
	void null_캡션은_확정_거짓() {
		assertEquals(Verdict.CONFIRMED_FALSE, GroupPurchaseRule.evaluate(null).verdict());
	}

	@Test
	void 빈_캡션은_확정_거짓() {
		assertEquals(Verdict.CONFIRMED_FALSE, GroupPurchaseRule.evaluate("").verdict());
	}

	/** 도구 어휘 20개 전부 + 정규식 형태가 '공구'와 결합하면 애매로 분류돼야 한다(스펙 §3). */
	@ParameterizedTest
	@ValueSource(strings = {
			"공구 없이 예쁘게 완성", "공구로 조립 완료", "공구 설치 방법", "공구 나사 종류", "드릴 공구 추천",
			"망치 공구 세트", "볼트 공구 사용법", "드라이버 공구 챙기기", "렌치 공구 소개", "톱 공구 리뷰",
			"목재 공구 구매", "목공 공구 필요", "철물점 공구 구경", "전동 공구 언박싱", "DIY 공구 추천",
			"수리 공구 필요", "공구함 구매", "공구통 정리", "공구박스 개봉", "공구세트 리뷰"})
	void 도구_어휘_각각이_공구와_결합하면_애매(String caption) {
		assertEquals(Verdict.AMBIGUOUS, GroupPurchaseRule.evaluate(caption).verdict(), caption);
	}

	@Test
	void DIY는_대소문자_무관하게_도구_어휘로_인식된다() {
		assertEquals(Verdict.AMBIGUOUS, GroupPurchaseRule.evaluate("diy 공구 소개").verdict());
	}

	@Test
	void 공구를_들고_형태의_정규식이_애매를_트리거한다() {
		assertEquals(Verdict.AMBIGUOUS, GroupPurchaseRule.evaluate("공구를 들고 나섰다").verdict());
		assertEquals(Verdict.AMBIGUOUS, GroupPurchaseRule.evaluate("공구가 필요해서 샀어요").verdict());
	}
}
