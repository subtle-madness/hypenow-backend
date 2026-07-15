package com.celfit.analytics.llm;

import java.util.Map;

/** 종합 텍스트 입력 — 분석 잡이 조립한 콘텐츠 1건의 전체 맥락. */
public record ContentToAnalyze(String shortCode, String accountHandle, String caption,
		String contentType, Long views, Long likes, Long comments,
		Map<String, Object> baseline, Map<String, Long> commentCategoryCounts) {
}
