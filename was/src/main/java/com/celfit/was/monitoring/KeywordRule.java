package com.celfit.was.monitoring;

import java.util.List;

/** 키워드 규칙 — 매칭 의미(and 전부 ∧ any 하나 이상 ∧ exclude 전무)는 monitoring 소유(계약 §3). */
public record KeywordRule(List<String> and, List<String> any, List<String> exclude) {
}
