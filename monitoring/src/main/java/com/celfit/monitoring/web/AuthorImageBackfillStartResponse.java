package com.celfit.monitoring.web;

import com.celfit.monitoring.service.AuthorImageBackfillCommandService;
import java.time.Instant;

/** POST /api/author-image-backfill 성공(202) 응답 — {@code {limit, startedAt}}. */
public record AuthorImageBackfillStartResponse(int limit, Instant startedAt) {

	public static AuthorImageBackfillStartResponse from(AuthorImageBackfillCommandService.Started started) {
		return new AuthorImageBackfillStartResponse(started.limit(), started.startedAt());
	}
}
