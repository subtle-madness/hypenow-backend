package com.celfit.analytics.llm;

/** VLM(이미지 분석) 포트 — F-2 스파이크 검증 전까지 기본 비활성. 테스트는 fake. */
public interface VisionPort {

	VlmResult analyze(String thumbnailUrl, String caption);
}
