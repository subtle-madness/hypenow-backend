package com.celfit.was.candidate;

import java.time.OffsetDateTime;
import java.util.List;

/** 후보 단건 응답 — REST 계약 (plans/2026-07-14-task-g-service-data.md §4). */
public record CandidateResponse(
		long id,
		String handle,
		CandidateStatus status,
		String memo,
		OffsetDateTime createdAt,
		OffsetDateTime updatedAt) {

	public static CandidateResponse from(Candidate candidate) {
		return new CandidateResponse(candidate.id(), candidate.handle(), candidate.status(),
				candidate.memo(), candidate.createdAt(), candidate.updatedAt());
	}

	/** 목록 응답 래퍼 — 페이징 등 확장 여지를 위해 배열을 그대로 내리지 않는다. */
	public record ListResponse(List<CandidateResponse> items) {

		public static ListResponse from(List<Candidate> candidates) {
			return new ListResponse(candidates.stream().map(CandidateResponse::from).toList());
		}
	}
}
