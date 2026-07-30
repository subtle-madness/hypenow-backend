package com.celfit.monitoring.service;

import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
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
	 *
	 * <p><b>데몬 스레드여야 한다.</b> 단일 스레드 executor의 코어 스레드는 한 번 생성되면
	 * 유휴 타임아웃 없이 영구히 살아남는데, non-daemon이면 SIGTERM 후 스프링 컨텍스트가 닫혀도
	 * JVM이 이 스레드를 기다리느라 종료되지 않는다 — 컨테이너 정지가 유예시간을 다 쓰고 SIGKILL로
	 * 끝난다. 데몬으로 두면 진행 중인 스윕이 종료 시 잘리지만, 그건 이미 정의된 동작이다:
	 * 중단된 실행은 {@code sweep_run.ok}가 true가 되지 않아 워터마크에서 자연 제외된다
	 * ({@link com.celfit.monitoring.store.SweepRunRepository} 클래스 주석).
	 */
	private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "manual-sweep");
		t.setDaemon(true);
		return t;
	});

	/**
	 * 종료 시 executor를 정리한다 — 데몬 스레드라 JVM 종료를 막지는 않지만, 컨텍스트가 닫힌 뒤에도
	 * 스윕이 DB·HTTP를 계속 건드리며 종료 로그를 어지럽히는 걸 막는다. 진행 중인 스윕의 완료를
	 * 기다리지 않는 것은 의도적이다(최대 1시간 블로킹된다).
	 */
	@PreDestroy
	void shutdown() {
		executor.shutdownNow();
	}

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
		try {
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
		} catch (RejectedExecutionException e) {
			// 종료 중(@PreDestroy 이후)에만 나는 경로 — Runnable이 아예 안 돌아 위 finally도 못 돈다.
			// 여기서 풀지 않으면 가드가 눌린 채 남는다.
			guard.release();
			throw e;
		}
		return new Started(runId, startedAt);
	}
}
