package com.celfit.analytics.analyze;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
