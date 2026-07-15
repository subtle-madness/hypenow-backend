package com.celfit.analytics.llm;

import java.util.List;
import java.util.Map;

/**
 * 계정 카피 입력 — 잡이 조립한 계정 1건의 전체 맥락 (전부 C1 미러 산출물).
 * posts는 올린 순, 캡션은 앞 300자 절단. hasAdComparison=false면 어댑터가 adHeadline 생성을 지시하지 않는다.
 */
public record AccountToAnalyze(String handle, Map<String, Object> summary,
		List<Map<String, Object>> categoryStats, List<Map<String, Object>> posts,
		boolean hasAdComparison) {
}
