package com.celfit.analytics.llm;

import java.util.List;

/** LLM 계정 카피 산출 — AccountReport의 문구 7종 (스펙 §1 표). */
public record AccountCopy(String tagline, String summary, String trendNote, String chartNote,
		List<String> traits, String adHeadline, String paceNote) {
}
