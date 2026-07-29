package com.celfit.was.monitoring;

import java.time.OffsetDateTime;

/** detected_candidate 1행(계약 §3). */
public record CandidateRow(long id, long targetId, String shortCode,
		OffsetDateTime detectedAt, String captionExcerpt, String status) {
}
