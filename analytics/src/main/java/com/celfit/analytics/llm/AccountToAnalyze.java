package com.celfit.analytics.llm;

import java.util.List;
import java.util.Map;

/**
 * 계정 카피 입력 — 잡이 조립한 계정 1건의 전체 맥락 (C1 미러 산출물 기반).
 * posts는 올린 순, 캡션은 앞 300자 절단. hasAdComparison=false면 어댑터가 adHeadline 생성을 지시하지 않는다.
 *
 * <p>광고 관련 값(hasAdComparison, summary의 organic_avg·ad_avg·ad_drop_pct·sponsored_count,
 * posts의 sponsored)은 미러 원본이 아니라 캡션 분류 정본(content_analyses.ad_type)으로
 * 치환된 값이다 — 산출 규칙은 AccountAdCanon 참조.
 */
public record AccountToAnalyze(String handle, Map<String, Object> summary,
		List<Map<String, Object>> categoryStats, List<Map<String, Object>> posts,
		boolean hasAdComparison) {
}
