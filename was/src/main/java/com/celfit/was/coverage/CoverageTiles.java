package com.celfit.was.coverage;

import java.time.LocalDate;

/** 요약 타일 값 — 미러 골격 행 수와 LLM 분석 진행 수. snapshotLatest는 스냅샷이 없으면 null. */
public record CoverageTiles(long contents, long accounts, long snapshots, LocalDate snapshotLatest, long analyses) {
}
