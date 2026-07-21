package com.celfit.analytics.llm;

import java.util.Map;

/**
 * 종합 텍스트 입력 — 분석 잡이 조립한 콘텐츠 1건의 전체 맥락.
 *
 * <p>adMarked = 인스타 유료 파트너십 태그(릴스 전용, 피드는 기능 자체가 없어 null).
 * 인스타가 보증하는 확정 사실이라 프롬프트에 사실로 싣는다 — 안 실으면 태그가 붙은 게시물을
 * LLM이 organic으로 뒤집는다(운영 실측 87건). 증거력은 단방향: 있으면 확정 광고, 없으면 판단 보류.
 */
public record ContentToAnalyze(String shortCode, String accountHandle, String caption,
		String contentType, Long views, Long likes, Long comments,
		Map<String, Object> baseline, Map<String, Long> commentCategoryCounts, Boolean adMarked) {
}
