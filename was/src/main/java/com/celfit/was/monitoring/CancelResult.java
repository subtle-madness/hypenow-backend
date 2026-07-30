package com.celfit.was.monitoring;

/** 해지 응답(계약 §2-3) — 멱등: 이미 종결이면 현재 상태 그대로 온다. */
public record CancelResult(long targetId, String status) {
}
