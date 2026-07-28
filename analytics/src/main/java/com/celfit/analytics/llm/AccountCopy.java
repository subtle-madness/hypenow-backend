package com.celfit.analytics.llm;

import java.util.List;

/** LLM 계정 카피 산출 — 리포트 개편(07-27) 5종: 태그라인·성향 태그·섹션 요약 3종. */
public record AccountCopy(String tagline, List<String> traits,
		String perfSummary, String contentSummary, String adSummary) {
}
