package com.celfit.monitoring.web;

import com.celfit.monitoring.store.SweepRunRow;
import java.time.Instant;

/**
 * GET /api/sweeps/latest 응답 — 실행 이력이 없으면 404가 아니라 200에 필드 전부 null로 내려간다
 * (이력 부재는 오류가 아니다). running은 DB의 completed_at IS NULL이 아니라 SweepGuard의 in-process
 * 상태로 판단한다 — 서버가 스윕 도중 크래시하면 completed_at이 영원히 비어(sweep_run 클래스 주석의
 * "ok=true만 정상 완주" 원칙과 같은 결) DB만으로는 "실행 중"과 "죽어서 멈춘 실행"을 구분할 수 없다.
 */
public record SweepStatusResponse(Long runId, Instant startedAt, Instant completedAt, Boolean ok, boolean running) {

	public static SweepStatusResponse from(SweepRunRow row, boolean running) {
		return new SweepStatusResponse(row.id(), row.startedAt(), row.completedAt(), row.ok(), running);
	}

	public static SweepStatusResponse empty(boolean running) {
		return new SweepStatusResponse(null, null, null, null, running);
	}
}
