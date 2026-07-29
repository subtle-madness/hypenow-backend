package com.celfit.was.monitoring;

import java.time.OffsetDateTime;

/** 기간 연장 응답(계약 §2-4). */
public record ExtendResult(long targetId, OffsetDateTime expiresAt) {
}
