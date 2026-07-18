package com.celfit.analytics.llm;

/**
 * LLM 일 한도(429 재시도 소진) — 잡은 이를 에러가 아닌 "잔여 이월" 신호로 받아 배치를 중단한다
 * (기존 "다음 실행 재대상" 컨벤션의 배치 단위 판).
 */
public class LlmQuotaExhaustedException extends RuntimeException {

	public LlmQuotaExhaustedException(String message) {
		super(message);
	}
}
