package com.celfit.analytics.admin;

import com.celfit.analytics.analyze.ProgressReporter;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;

/**
 * 잡별 진행률 스냅샷 — 잡 스레드가 쓰고 UI 스레드가 읽는다.
 * 값 정합보다 가시성이 목적이라 잡별 단일 뮤터블 홀더 + synchronized 로 충분.
 */
public class JobProgressRegistry {

	public record Progress(boolean running, int processed, int failed, int total, Instant startedAt) {
		static final Progress EMPTY = new Progress(false, 0, 0, 0, null);
	}

	private final Map<JobName, Progress> byJob = new EnumMap<>(JobName.class);

	public synchronized void start(JobName job) {
		byJob.put(job, new Progress(true, 0, 0, 0, Instant.now()));
	}

	/** 잡 종료 — 마지막 진행값은 보존하고 running만 내린다 (피드 기록·최근값 참조용). */
	public synchronized void finish(JobName job) {
		Progress p = byJob.getOrDefault(job, Progress.EMPTY);
		byJob.put(job, new Progress(false, p.processed(), p.failed(), p.total(), p.startedAt()));
	}

	public ProgressReporter reporter(JobName job) {
		return (processed, failed, total) -> {
			synchronized (this) {
				Progress p = byJob.getOrDefault(job, Progress.EMPTY);
				byJob.put(job, new Progress(p.running(), processed, failed, total, p.startedAt()));
			}
		};
	}

	public synchronized Progress snapshot(JobName job) {
		return byJob.getOrDefault(job, Progress.EMPTY);
	}
}
