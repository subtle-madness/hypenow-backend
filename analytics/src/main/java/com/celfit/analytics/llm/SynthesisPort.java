package com.celfit.analytics.llm;

/** 종합 텍스트 포트 — 테스트는 fake (실 API 금지). */
public interface SynthesisPort {

	Synthesis synthesize(ContentToAnalyze content);
}
