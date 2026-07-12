package com.celfit.analytics.llm;

/** 분류 출력 — 6분류 중 하나. 어휘: purchase·question·positive·adAware·friendTag·etc */
public record ClassifiedComment(long id, String category) {
}
