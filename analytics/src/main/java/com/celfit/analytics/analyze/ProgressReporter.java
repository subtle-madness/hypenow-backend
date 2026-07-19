package com.celfit.analytics.analyze;

/**
 * 잡 진행률 보고 경계 — 잡이 admin 층에 의존하지 않게 하는 함수형 인터페이스.
 * one-shot CLI처럼 어드민이 없는 컨텍스트는 NOOP 주입.
 */
@FunctionalInterface
public interface ProgressReporter {

	ProgressReporter NOOP = (processed, failed, total) -> { };

	void report(int processed, int failed, int total);
}
