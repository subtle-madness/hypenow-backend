package com.celfit.monitoring.store;

import java.time.Instant;

/** sweep_run 1행 — GET /api/sweeps/latest 조회용. completedAt·ok는 아직 진행 중이면 null. */
public record SweepRunRow(long id, Instant startedAt, Instant completedAt, Boolean ok) {}
