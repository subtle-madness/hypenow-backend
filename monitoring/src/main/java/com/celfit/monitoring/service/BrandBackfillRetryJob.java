package com.celfit.monitoring.service;

import com.celfit.monitoring.store.BrandRepository;
import com.celfit.monitoring.store.BrandRow;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 열거 실패 재시도 잡(2026-09, 결함 1) — 초기 백필 열거({@code sweepCore})가 실패해
 * {@code backfill_error}가 찍히고 아직 한 번도 완주 못 한({@code last_swept_on IS NULL}) 브랜드를
 * 최대 {@code maxAttempts}회, 선형 백오프로 재제출한다. 종전엔 재시도 주체가 없어 복구가 익일
 * KST 02:00 정기 스윕뿐이었는데, 사용자에게 내려가는 문구는 "자동으로 재시도 중이에요"라 사실과
 * 달랐다({@link BrandRegistrationService} 문구 상수 참조).
 *
 * <p><b>중복 실행 방지 3중</b> — ① 틱 자체의 재진입 방지는 {@link BrandBackfillRetryScheduler}의
 * {@code AtomicBoolean}. ② 야간 브랜드 스윕과의 겹침은 {@link BrandSweepGuard#isRunning()} —
 * 스케줄러가 틱 전체를 스킵한다. ③ 브랜드 단위 in-flight — 재시도 1회는 수 분 걸리므로 이게 없으면
 * 다음 틱이 같은 브랜드를 또 제출한다. 프로세스 로컬 상태다(DB에 두면 프로세스 사망 시 영구 락 —
 * {@link BrandSweepGuard} javadoc과 같은 근거).
 *
 * <p><b>등록·기간 확장 제출 경로에는 이 in-flight 가드를 두지 않는다</b>(의도) — 거기에 넣으면
 * "등록 백필 진행 중 기간 확장 요청"이 조용히 스킵돼 확장 창이 야간 스윕까지 미뤄지는 행동 변화가
 * 생긴다. 재시도만 반복 틱을 가지므로 가드도 여기만 필요하다. 재시도 ↔ 등록/확장이 겹치는 희귀
 * 케이스는 기존에 이미 수용된 성질(멱등 upsert, 콜 낭비만 발생)에 그대로 해당한다.
 *
 * <p>시도 카운트({@code backfill_attempts})는 <b>제출 직전</b> {@link BrandRepository#markBackfillAttempt}로
 * 증가한다 — 완료 시 증가는 재시도 도중 프로세스가 죽으면 예산이 환불돼 무한 재시도가 된다. 성공은
 * {@link BrandRepository#touchSwept}가 {@code backfill_error}와 함께 0으로 클리어한다(유일한 해제 지점).
 */
@Component
public class BrandBackfillRetryJob {

	private static final Logger log = LoggerFactory.getLogger(BrandBackfillRetryJob.class);

	private final BrandRepository brands;
	private final BrandRegistrationService registration;
	private final int maxAttempts;
	private final int maxAgeMinutes;
	private final int backoffMinutes;
	private final int batch;
	/** 재시도가 진행 중인 브랜드 id — 완료 시(성공·실패 무관) whenComplete에서 제거한다. */
	private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();

	public BrandBackfillRetryJob(BrandRepository brands, BrandRegistrationService registration,
			@Value("${monitoring.brand.backfill-retry.max-attempts:3}") int maxAttempts,
			@Value("${monitoring.brand.backfill-retry.max-age:6h}") Duration maxAge,
			@Value("${monitoring.brand.backfill-retry.backoff:5m}") Duration backoff,
			@Value("${monitoring.brand.backfill-retry.batch:5}") int batch) {
		this.brands = brands;
		this.registration = registration;
		this.maxAttempts = maxAttempts;
		this.maxAgeMinutes = (int) maxAge.toMinutes();
		this.backoffMinutes = (int) backoff.toMinutes();
		this.batch = batch;
	}

	/**
	 * 틱 1회 — 후보 조회 → 상한 소진 브랜드 문구 교체 → 후보마다 in-flight 등록 후 제출. 이 메서드
	 * 자체는 블로킹하지 않는다(재시도 실행은 {@link BrandRegistrationService#retryBackfillAsync}가
	 * backfill executor로 넘긴다) — 스케줄링 풀 스레드를 오래 붙들지 않는다.
	 */
	public void run() {
		List<BrandRow> candidates = brands.findBackfillRetryCandidates(maxAttempts, maxAgeMinutes,
				backoffMinutes, batch);
		int exhausted = brands.markBackfillRetryExhausted(
				BrandRegistrationService.BACKFILL_FAILED_RETRY_EXHAUSTED, maxAttempts);
		if (exhausted > 0) {
			log.info("브랜드 백필 재시도 상한 소진 — 문구 교체 {}건", exhausted);
		}
		for (BrandRow row : candidates) {
			if (!inFlight.add(row.id())) {
				log.debug("브랜드 백필 재시도 스킵 — 이미 진행 중 brandId={}", row.id());
				continue;
			}
			brands.markBackfillAttempt(row.id());
			log.info("브랜드 백필 재시도 제출 brandId={}, username={}", row.id(), row.username());
			registration.retryBackfillAsync(row).whenComplete((v, e) -> inFlight.remove(row.id()));
		}
	}
}
