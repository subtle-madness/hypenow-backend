package com.celfit.analytics.llm;

/**
 * ②속성 추출 + ③콘텐츠 종합 통합 포트 (2026-07-18 확정 — 캡션·시스템 프롬프트 중복 제거, 호출 수 절반).
 * Gemini 구현은 1콜, Anthropic 구현은 기존 어댑터 2콜 컴포지트(롤백 경로).
 */
public interface ContentInsightPort {

	record ContentInsight(ContentAttributes attributes, Synthesis synthesis) {}

	/** @param thumbnailUrl null이면 캡션만으로 속성 분석. 캡션·썸네일 모두 없는 판단은 호출자(잡) 몫. */
	ContentInsight analyze(ContentToAnalyze content, String thumbnailUrl);
}
