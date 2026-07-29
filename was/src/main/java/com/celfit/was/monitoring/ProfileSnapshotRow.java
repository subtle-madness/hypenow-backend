package com.celfit.was.monitoring;

import java.time.LocalDate;

/** profile_snapshot 1행 — captured_on 일 1회 upsert(KST), 등록 당일은 1행뿐(계약 §5). */
public record ProfileSnapshotRow(LocalDate capturedOn, Long followers, Long following,
		Long mediaCount) {
}
