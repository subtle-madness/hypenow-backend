package com.celfit.monitoring.service;

import com.celfit.monitoring.image.AuthorImageBackfillJob;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 만료된 CDN 프로필 이미지 재수집 백필 수동 트리거 — {@link SweepCommandService}와 동형(전용 단일
 * 스레드 executor + 데몬 스레드 + {@link AuthorImageBackfillGuard} CAS로 동시 실행 방지). 이 백필은
 * 대상 수만큼 Hiker 재조회 콜을 내고(계정당 최대 1콜, {@code limit}이 총량 상한) 그 뒤 아카이브
 * 잡까지 곧바로 돌려 다운로드·업로드도 하므로 수 분 걸릴 수 있다 — HTTP 타임아웃을 피하려면
 * 반드시 비동기로 실행해야 한다(스윕과 동일 근거).
 */
@Service
public class AuthorImageBackfillCommandService {

	private static final Logger log = LoggerFactory.getLogger(AuthorImageBackfillCommandService.class);

	private final AuthorImageBackfillJob job;
	private final AuthorImageBackfillGuard guard;

	/** 데몬 스레드 — SweepCommandService.executor와 동일 근거(SIGTERM 후 JVM이 이 스레드를 기다리지 않게). */
	private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "author-image-backfill");
		t.setDaemon(true);
		return t;
	});

	@PreDestroy
	void shutdown() {
		executor.shutdownNow();
	}

	public AuthorImageBackfillCommandService(AuthorImageBackfillJob job, AuthorImageBackfillGuard guard) {
		this.job = job;
		this.guard = guard;
	}

	/** 202 응답에 실을 결과. */
	public record Started(int limit, Instant startedAt) {}

	/**
	 * 백필 1회를 비동기로 시작한다. 이미 실행 중이면 {@link AuthorImageBackfillAlreadyRunningException}을
	 * 던진다(컨트롤러가 409로 매핑).
	 */
	public Started start(int limit) {
		if (!guard.tryAcquire()) {
			throw new AuthorImageBackfillAlreadyRunningException();
		}
		Instant startedAt = Instant.now();
		try {
			executor.submit(() -> {
				try {
					job.run(limit);
				} catch (RuntimeException e) {
					// executor 안에서 삼키지 않으면 조용히 사라진다 — 반드시 로그로 남긴다.
					log.error("이미지 백필 실행 실패(limit={})", limit, e);
				} finally {
					guard.release();
				}
			});
		} catch (RejectedExecutionException e) {
			// 종료 중(@PreDestroy 이후)에만 나는 경로 — Runnable이 아예 안 돌아 위 finally도 못 돈다.
			guard.release();
			throw e;
		}
		return new Started(limit, startedAt);
	}
}
