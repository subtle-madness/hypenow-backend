package com.celfit.monitoring.store;

import com.celfit.monitoring.domain.CandidateStatus;

/** detected_candidate 한 행 — 검토 대상 후보 게시물. */
public record CandidateRow(long id, long targetId, String shortCode, CandidateStatus status) {}
