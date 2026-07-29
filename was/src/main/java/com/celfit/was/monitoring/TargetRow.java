package com.celfit.was.monitoring;

import java.time.OffsetDateTime;

/** monitoring DB target 1행(계약 §3) — status·fail_reason 어휘는 monitoring이 확정, 해석 없이 전달. */
public record TargetRow(long id, String type, String username, String shortCode,
		String keywordRule, String status, String trackedShortCode, OffsetDateTime trackedSince,
		String registrationKey, OffsetDateTime expiresAt, OffsetDateTime registeredAt,
		OffsetDateTime closedAt, OffsetDateTime lastFetchedAt, String failReason) {
}
