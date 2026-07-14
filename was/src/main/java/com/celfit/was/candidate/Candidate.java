package com.celfit.was.candidate;

import java.time.OffsetDateTime;

/** app.candidates 행 record — 컴포넌트 순서 = SELECT 컬럼 순서 = V1 DDL. */
public record Candidate(
		long id,
		String handle,
		CandidateStatus status,
		String memo,
		OffsetDateTime createdAt,
		OffsetDateTime updatedAt) {
}
