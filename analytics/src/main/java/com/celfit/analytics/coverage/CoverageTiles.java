package com.celfit.analytics.coverage;

/** analysis DB 요약 타일 — 미러 골격 행 수와 LLM 분석 진행 수. */
public record CoverageTiles(long contents, long accounts, long analyses) {
}
