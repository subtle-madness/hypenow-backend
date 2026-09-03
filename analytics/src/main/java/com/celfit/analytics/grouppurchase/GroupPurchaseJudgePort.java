package com.celfit.analytics.grouppurchase;

/**
 * 애매분(규칙 §3의 AMBIGUOUS) 전용 LLM 판정 포트 — 테스트는 fake로 대체한다.
 * 규칙으로 확정되는 캡션은 이 포트를 아예 호출하지 않는다.
 */
public interface GroupPurchaseJudgePort {

	/** @param caption 판정 대상 캡션(null 가능 — 호출부가 이미 규칙으로 걸렀으므로 실제로는 발생하지 않는다) */
	Judgment judge(String caption);

	record Judgment(boolean groupPurchase, String reason) {}
}
