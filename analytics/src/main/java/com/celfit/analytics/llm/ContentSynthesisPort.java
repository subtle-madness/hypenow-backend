package com.celfit.analytics.llm;

/** 해석 문구 전용 LLM 어댑터 — 저장된 사실 + 새 기준선 → Synthesis 5필드. */
public interface ContentSynthesisPort {

	Synthesis synthesize(ContentToSynthesize content);
}
