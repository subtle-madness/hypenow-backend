package com.celfit.was.monitoring;

import java.time.OffsetDateTime;

/** 기간 연장 요청(계약 §2-4). */
public record ExtendRequest(OffsetDateTime expiresAt) {
}
