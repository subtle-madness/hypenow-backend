package com.celfit.was.monitoring;

import java.time.LocalDate;

/** post_snapshot 1행 — 취득 불가 지표는 null(피드 조회수 등, 계약 §3 null 규칙). */
public record PostSnapshotRow(LocalDate capturedOn, String contentType, Long likes,
		Long comments, Long views, Long saves, Long shares, Long reposts) {
}
