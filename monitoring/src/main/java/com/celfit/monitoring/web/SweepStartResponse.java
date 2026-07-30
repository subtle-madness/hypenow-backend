package com.celfit.monitoring.web;

import com.celfit.monitoring.service.SweepCommandService;
import java.time.Instant;

/**
 * POST /api/sweeps 성공(202) 응답 — {@code {runId, startedAt}}. runId는 sweep_run 기록 자체가
 * 격리 실패한 극히 드문 경우에만 null이다(DailySweepJob의 기존 start 격리 계약, SweepCommandService 참고).
 */
public record SweepStartResponse(Long runId, Instant startedAt) {

	public static SweepStartResponse from(SweepCommandService.Started started) {
		return new SweepStartResponse(started.runId(), started.startedAt());
	}
}
