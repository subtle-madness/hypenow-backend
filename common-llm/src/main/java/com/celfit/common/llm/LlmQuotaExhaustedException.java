package com.celfit.common.llm;

/**
 * Vertex 429 재시도 소진 — 호출자가 "에러"가 아니라 "일시 용량 부족, 잔여 이월" 신호로 받도록
 * 별도 타입으로 구분한다(analytics {@code LlmQuotaExhaustedException}과 동형, 08-18 이식 —
 * 이식 출처: analytics/src/main/java/com/celfit/analytics/llm/LlmQuotaExhaustedException.java).
 */
public class LlmQuotaExhaustedException extends RuntimeException {

	public LlmQuotaExhaustedException(String message) {
		super(message);
	}
}
