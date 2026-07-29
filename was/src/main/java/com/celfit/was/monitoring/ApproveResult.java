package com.celfit.was.monitoring;

/** 승인 응답(계약 §2-2) — WATCHING → TRACKING 전환 결과. */
public record ApproveResult(long targetId, String status, String trackedShortCode) {
}
