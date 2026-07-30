package com.celfit.analytics.analyze;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.celfit.analytics.llm.AccountCopy;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 어휘 통제(2026-07-29 스펙 §3-2): 어휘 밖 드롭 + 중복 제거 + 5개 상한. 빈 결과는 허용(빈 배열 저장). */
class AccountAnalysisWriterTest {

	static final Set<String> VOCAB = Set.of("솔직 리뷰", "릴스 중심", "브이로그", "감성 무드", "코덕", "데일리룩");

	@Test
	void 어휘_밖_값은_드롭하고_중복은_접는다() {
		List<String> out = AccountAnalysisWriter.sanitize(
				List.of("솔직 리뷰", "솔직한 후기", "릴스 중심", "솔직 리뷰"), VOCAB);
		assertEquals(List.of("솔직 리뷰", "릴스 중심"), out);
	}

	@Test
	void 전부_어휘_밖이면_빈_배열이_된다() {
		assertEquals(List.of(), AccountAnalysisWriter.sanitize(List.of("아무말", "조어"), VOCAB));
	}

	@Test
	void 상한_5개는_sanitize_후에_적용된다() {
		List<String> out = AccountAnalysisWriter.sanitize(
				List.of("솔직 리뷰", "없는값", "릴스 중심", "브이로그", "감성 무드", "코덕", "데일리룩"), VOCAB);
		assertEquals(List.of("솔직 리뷰", "릴스 중심", "브이로그", "감성 무드", "코덕"), out);
	}

	private static AccountCopy copyWithPerf(String perfSummary) {
		return new AccountCopy("태그라인", List.of("솔직 리뷰"), perfSummary, "콘텐츠 요약", "");
	}

	/**
	 * 관측 전용 감지(2026-07-30) — 지시 정리(LlmGuard.ACCOUNT_RULES)가 유일한 강제 수단이라
	 * 완벽을 보장 못 하므로, 재발 시(0_tsuki2류) 알아챌 신호. 차단은 하지 않는다.
	 */
	@Test
	void perfSummary에_숫자가_있으면_감지한다() {
		assertTrue(AccountAnalysisWriter.hasNumericCitation(
				copyWithPerf("평균 좋아요 수는 1,605개 수준입니다.")));
	}

	@Test
	void perfSummary가_수준_표현만_쓰면_감지하지_않는다() {
		assertFalse(AccountAnalysisWriter.hasNumericCitation(
				copyWithPerf("팔로워 대비 높은 편이며 완만한 상승세를 보입니다.")));
	}
}
