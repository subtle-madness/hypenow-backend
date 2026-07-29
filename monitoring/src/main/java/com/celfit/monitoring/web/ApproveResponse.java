package com.celfit.monitoring.web;

/** 후보 승인 응답 — 계약 §2-2 `{targetId, status, trackedShortCode}`. */
public record ApproveResponse(long targetId, String status, String trackedShortCode) {}
