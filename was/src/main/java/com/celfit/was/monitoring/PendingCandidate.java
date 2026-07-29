package com.celfit.was.monitoring;

import java.time.OffsetDateTime;

/** 워터마크 이후 신규 PENDING 후보 + 소속 캠페인 계정(계약 §3 알람 쿼리) — 이메일 크론 대비. */
public record PendingCandidate(long id, long targetId, String shortCode,
		String captionExcerpt, OffsetDateTime detectedAt, String username) {
}
