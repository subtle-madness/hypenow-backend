package com.celfit.monitoring.web;

/** 후보 기각 응답 — 계약 §2-3 `{candidateId, status}`. 캠페인 상태는 안 바뀌므로 싣지 않는다. */
public record RejectResponse(long candidateId, String status) {}
