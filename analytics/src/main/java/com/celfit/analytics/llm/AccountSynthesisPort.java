package com.celfit.analytics.llm;

/** 계정 카피 포트 — 테스트는 fake (실 API 금지). */
public interface AccountSynthesisPort {

	AccountCopy synthesize(AccountToAnalyze account);
}
