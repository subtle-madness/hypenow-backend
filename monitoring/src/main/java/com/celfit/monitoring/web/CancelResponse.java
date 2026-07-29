package com.celfit.monitoring.web;

/** 해지 응답 — 계약 §2-5 `{targetId, status}`. 멱등이라 이미 종결된 캠페인이면 현재 상태가 실린다. */
public record CancelResponse(long targetId, String status) {}
