package com.celfit.monitoring.service;

import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 일일 스윕 수동 1회 트리거 — test 환경은 스케줄 전부 비활성(K 원칙)이라 인플루언서 감지 동작을
 * 검증하려면 이 API로 스윕을 직접 돌려야 한다. {@link DailySweepJob#run()}은 일시 실패 재시도
 * 라운드(간격 × 라운드, 기본 10m→20m→30m)를 돌아 최대 1시간 블로킹될 수 있어 반드시 비동기로
 * 실행한다 — 동기로 두면 HTTP 타임아웃이다.
 */
@Service
public class SweepCommandService {

	private static final Logger log = LoggerFactory.getLogger(SweepCommandService.class);

	private final DailySweepJob job;
	private final SweepGuard guard;

	/**
	 * 전용 단일 스레드 executor — application.yml의 {@code spring.task.scheduling.pool.size: 2}
	 * 코멘트에 이미 적혀 있듯, 스윕의 재시도 라운드 대기가 스케줄러 풀 스레드를 붙잡으면 알람 발송
	 * 틱이 그 사이 통째로 밀린다. 수동 트리거는 그 풀과 아예 무관한 스레드에서 돌려야 같은 문제가
	 * 되풀이되지 않는다 — 그래서 스프링 TaskScheduler를 재사용하지 않고 별도 executor를 둔다.
	 */
	private final ExecutorService executor = Executors.newSingleThreadExecutor(
			r -> new Thread(r, "manual-sweep"));

	public SweepCommandService(DailySweepJob job, SweepGuard guard) {
		this.job = job;
		this.guard = guard;
	}

	/** 202 응답에 실을 결과 — runId는 sweep_run 기록 자체가 격리 실패하면 null일 수 있다(극히 드묾, DailySweepJob 참고). */
	public record Started(Long runId, Instant startedAt) {}

	/**
	 * 스윕 1회를 비동기로 시작한다. 이미 실행 중(크론이든 이전 수동 트리거든)이면
	 * {@link SweepAlreadyRunningException}을 던진다(컨트롤러가 409로 매핑).
	 *
	 * <p>runId를 응답에 실으려면 sweep_run INSERT가 이 메서드 반환 전에 끝나야 한다 — 그래서
	 * {@link DailySweepJob#startSweepRun()}만 여기서 동기로 먼저 부르고, 실제 스윕 본체
	 * ({@link DailySweepJob#runWithId(Long)})는 executor에 넘긴다. {@link DailySweepJob#run()}을
	 * 건드리지 않고 이 둘로 쪼갠 덕에 run()의 기존 격리 계약(기록 실패가 스윕을 막지 않음, ok=false
	 * 재전파)이 그대로 보존된다.
	 */
	public Started start() {
		if (!guard.tryAcquire()) {
			throw new SweepAlreadyRunningException();
		}
		Long runId;
		try {
			runId = job.startSweepRun();
		} catch (RuntimeException e) {
			// startSweepRun 자체가 내부에서 격리하므로 사실상 여기까지 새지 않지만, 혹시 새더라도
			// 가드를 여기서 풀어야 한다 — 아래 executor.submit의 finally가 못 도는 경로다.
			guard.release();
			throw e;
		}
		Instant startedAt = Instant.now();
		executor.submit(() -> {
			try {
				job.runWithId(runId);
			} catch (RuntimeException e) {
				// executor 안에서 삼키지 않으면 조용히 사라진다 — 반드시 로그로 남긴다.
				log.error("수동 스윕 실행 실패(runId={})", runId, e);
			} finally {
				guard.release();
			}
		});
		return new Started(runId, startedAt);
	}
}
