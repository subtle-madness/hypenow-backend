package com.celfit.was.monitoring;

/** 기각 응답(계약 §2-3) — 후보만 닫히고 캠페인은 WATCHING 지속. */
public record RejectResult(long candidateId, String status) {
}
