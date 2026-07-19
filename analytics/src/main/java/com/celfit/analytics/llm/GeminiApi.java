package com.celfit.analytics.llm;

/** Gemini generateContent 1콜 추상화 — 어댑터는 이 인터페이스만 보고, 테스트는 fake로 대체한다. */
public interface GeminiApi {

	record InlineImage(String mimeType, byte[] data) {}

	/**
	 * 구조화 JSON 출력 1콜. schemaJson은 Gemini responseSchema(OpenAPI 스타일 — additionalProperties
	 * 불가, nullable 사용) JSON 텍스트. 반환은 응답 본문 텍스트(JSON).
	 * 일 한도 소진(429 재시도 소진)은 {@link LlmQuotaExhaustedException}.
	 */
	String generateJson(String model, String systemInstruction, String userText,
			InlineImage image, String schemaJson, int maxOutputTokens);
}
