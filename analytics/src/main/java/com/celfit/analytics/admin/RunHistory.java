package com.celfit.analytics.admin;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * 실행 피드용 인메모리 링 버퍼 — DB 이력 테이블 안 둠(07-17 결정 유지), 재시작 시 소실 수용.
 */
public class RunHistory {

	public enum Outcome { SUCCESS, FAILED, QUOTA_CARRYOVER, ERROR }

	public record Run(JobName job, TriggerType trigger, Instant startedAt, Instant endedAt,
			Outcome outcome, int processed, int failed, String note) {
	}

	private final int capacity;
	private final Deque<Run> runs = new ArrayDeque<>();

	public RunHistory(int capacity) {
		this.capacity = capacity;
	}

	public synchronized void record(Run run) {
		runs.addFirst(run);
		while (runs.size() > capacity) {
			runs.removeLast();
		}
	}

	public synchronized List<Run> recent(int limit) {
		return runs.stream().limit(limit).toList();
	}
}
