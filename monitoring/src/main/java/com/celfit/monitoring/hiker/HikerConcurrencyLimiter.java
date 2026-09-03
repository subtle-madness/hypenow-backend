package com.celfit.monitoring.hiker;

import com.celfit.instagram.source.HikerFetchException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Hiker 전송 동시 in-flight 상한(2026-09-03 브랜드 병렬 스윕) — "전역 동시 콜 최대 14"라는 예산이
 * 지금까지는 <b>풀 크기의 산술 합</b>으로만 지켜졌다(보강 워커 10 + 스윕 core 1 + 등록 core 2 +
 * 해시태그 스윕 1 — {@link com.celfit.monitoring.config.BrandBackfillConfig} javadoc). 브랜드 루프가
 * 병렬이 되면 스윕 몫이 브랜드 수만큼 늘어 그 산술이 깨지므로, 예산을 <b>전송 계층 세마포어</b>로
 * 옮겨 어떤 풀 조합에서도 구조적으로 성립하게 한다. 근거 수치는 08-12/13 동시성 램프 실측(동시
 * 12부터 꼬리가 붙는다는 상한 근거는 08-13 재실측에서 서지 않았고 14가 실측이 지지하는 구간)과
 * 힙 예산(동시 in-flight 콜당 ~10MB)이다.
 *
 * <p><b>자체크롤(self) 전송은 이 상한과 무관하다</b> — self는 {@code SelfHttpClient}로 나가고 이
 * 데코레이터는 {@code HikerHttp} 체인에만 끼기 때문이다. self 실패로 Hiker 폴백이 나갈 때만 그
 * 콜이 여기 합류한다(그게 옳다 — 그 순간엔 진짜 Hiker 콜이다).
 *
 * <h2>동기(사용자 대면) 경로 기아 방지 — 예약 퍼밋</h2>
 * 단일 공정(FIFO) 세마포어 하나로는 부족하다: 대기 중인 동기 콜의 최악 대기가 "가장 오래 걸리는
 * in-flight 콜의 잔여 시간"(재시도 포함 수십 초)까지 늘어나 was의 10초 예산을 넘길 수 있다.
 * 그래서 <b>총 permits는 max, 배치 레인은 max - reserved까지만</b> 쓰게 두 겹으로 건다. 그러면
 * 배치가 아무리 몰려도 여유 퍼밋이 항상 reserved개 남아 있고, <b>동기 콜은 다른 동기 콜이
 * reserved개 이상 떠 있을 때만 대기</b>한다 — 즉 배치는 구조적으로 동기를 굶힐 수 없다. 동기
 * 콜은 원래 동시성이 매우 낮은(HTTP 요청 스레드에서 몇 건) 경로라 이 대기는 사실상 도달하지
 * 않는다.
 *
 * <p>그럼에도 넘치면 짧은 예산(기본 5초)으로 끊고 {@link HikerFetchException}을 던진다 — 이는
 * {@code HikerFirstInstagramSource}가 <b>벤더 장애</b>로 보고 self 구조 시도로 넘기는 예외라,
 * 무한 대기보다 낫다. 배치 레인의 예산(기본 180초)은 지연 예산이 아니라 <b>교착 안전 밸브</b>다 —
 * 배치는 기다리는 사람이 없어 대기가 곧 의도한 백프레셔이고, 짧게 잡으면 보강 워커(10) + 브랜드
 * 열거(3)가 배치 퍼밋(12)을 두고 겹치는 평범한 야간 부하에서 헛실패가 난다. 초과하면 그 한 건만
 * 실패해 호출부의 건 단위 격리(로그만)로 흡수된다.
 *
 * <p>두 세마포어를 batch → all 순으로만 획득하고 동기는 all만 획득하므로 대기 사이클(교착)이
 * 성립하지 않는다. 둘 다 공정(fair) 모드라 대기열이 FIFO다.
 */
public class HikerConcurrencyLimiter {

	private static final Logger log = LoggerFactory.getLogger(HikerConcurrencyLimiter.class);
	static final String TIMEOUT_METRIC = "hiker.concurrency.timeout";
	static final String AVAILABLE_METRIC = "hiker.concurrency.available";

	/** 배치·동기를 합친 전역 상한. */
	public enum Lane {
		/** 스케줄·백그라운드 경로(스윕·백필·보강·지표 재시도) — 예약분을 침범하지 않는다. */
		BATCH,
		/** 사용자 대면 동기 HTTP 경로 — 예약 퍼밋 덕에 배치에 밀리지 않는다. */
		SYNC
	}

	private final Semaphore all;
	private final Semaphore batch;
	private final Duration batchTimeout;
	private final Duration syncTimeout;
	private final MeterRegistry registry;

	public HikerConcurrencyLimiter(int maxConcurrentCalls, int syncReservedPermits, Duration batchTimeout,
			Duration syncTimeout, MeterRegistry registry) {
		int max = Math.max(1, maxConcurrentCalls);
		int reserved = Math.clamp(syncReservedPermits, 0, max - 1);
		this.all = new Semaphore(max, true);
		this.batch = new Semaphore(max - reserved, true);
		this.batchTimeout = batchTimeout;
		this.syncTimeout = syncTimeout;
		this.registry = registry;
		gauge("all", all);
		gauge("batch", batch);
	}

	public <T> T call(Lane lane, Supplier<T> body) {
		if (lane == Lane.BATCH) {
			acquire(batch, batchTimeout, lane, "배치 레인");
			try {
				return withGlobalPermit(lane, batchTimeout, body);
			} finally {
				batch.release();
			}
		}
		return withGlobalPermit(lane, syncTimeout, body);
	}

	private <T> T withGlobalPermit(Lane lane, Duration timeout, Supplier<T> body) {
		acquire(all, timeout, lane, "전역");
		try {
			return body.get();
		} finally {
			all.release();
		}
	}

	private void acquire(Semaphore semaphore, Duration timeout, Lane lane, String what) {
		try {
			if (!semaphore.tryAcquire(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
				count(lane);
				log.warn("Hiker 동시 콜 {} 상한 대기 초과({}) — 이번 콜 포기 lane={}", what, timeout, lane);
				throw new HikerFetchException(
						"Hiker 동시 콜 상한 대기 초과(%s, %s): %s".formatted(what, timeout, lane));
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new HikerFetchException("Hiker 동시 콜 상한 대기 중 인터럽트: " + lane);
		}
	}

	/** 관측 실패가 수집을 죽이면 안 된다(TimedHikerHttp와 같은 원칙) — 기록 실패는 로그만. */
	private void count(Lane lane) {
		try {
			Counter.builder(TIMEOUT_METRIC).tag("lane", lane.name().toLowerCase(java.util.Locale.ROOT))
					.register(registry).increment();
		} catch (RuntimeException e) {
			log.warn("Hiker 동시 콜 상한 지표 기록 실패(무시): {}", e.toString());
		}
	}

	private void gauge(String lane, Semaphore semaphore) {
		try {
			Gauge.builder(AVAILABLE_METRIC, semaphore, Semaphore::availablePermits)
					.tag("lane", lane).register(registry);
		} catch (RuntimeException e) {
			log.warn("Hiker 동시 콜 여유 퍼밋 게이지 등록 실패(무시): {}", e.toString());
		}
	}
}
