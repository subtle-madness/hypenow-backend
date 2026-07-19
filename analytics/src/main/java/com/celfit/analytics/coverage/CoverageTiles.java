package com.celfit.analytics.coverage;

import java.time.LocalDate;

/** analysis DB 요약 타일 — 미러 골격 행 수와 LLM 분석 진행 수. snapshotLatest는 스냅샷이 없으면 null. */
public record CoverageTiles(long contents, long accounts, long snapshots, LocalDate snapshotLatest, long analyses) {
}
