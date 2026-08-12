package com.celfit.monitoring.llm;

/** Gemini HTTP 전송 격리 — 테스트에서 fake로 대체(HikerHttp와 동형 seam). */
@FunctionalInterface
public interface GeminiHttp {

	/** path는 base-url 이후(예: "/v1beta/models/모델명:generateContent"). */
	String post(String path, String jsonBody);
}
