package com.celfit.analytics.analyze;

/** 잡 실행 결과 — 실행 피드(outcome 판정)용. carriedOver=일 한도 소진으로 잔여 이월. */
public record JobResult(int processed, int failed, boolean carriedOver) {
}
