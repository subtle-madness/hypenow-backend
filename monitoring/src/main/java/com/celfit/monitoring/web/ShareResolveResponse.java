package com.celfit.monitoring.web;

/** share 해소 응답 — 계약 §2-6. contentType은 REELS/FEED. */
public record ShareResolveResponse(String shortCode, String username, String contentType) {}
