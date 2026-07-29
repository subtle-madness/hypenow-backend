package com.celfit.monitoring.web;

import java.time.Instant;

/** 기간 연장 응답 — 계약 §2-4 `{targetId, expiresAt}`. expiresAt은 ISO-8601 UTC로 나간다. */
public record ExtendResponse(long targetId, Instant expiresAt) {}
