package com.celfit.monitoring.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.monitoring.domain.BrandStatus;
import com.celfit.monitoring.store.BrandRow;
import com.celfit.monitoring.testsupport.InMemoryBrands;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * 열거 실패 재시도 잡(2026-09, 결함 1) — 후보 선정·in-flight 중복 방지·상한 소진 문구 교체를
 * 검증한다. {@link BrandRegistrationService#runBackfillSafely}의 실제 재시도 성공/실패 갈래
 * 자체(sweepCore·enrich 배선)는 {@code BrandRegistrationServiceTest}가 이미 덮으므로, 여기서는
 * {@link BrandRegistrationService}를 얇게 스텁해 이 잡이 "언제 무엇을 제출하는가"만 관찰한다 —
 * {@link RecordingRegistration#retryBackfillAsync}가 재시도의 DB 쪽 결과(touchSwept 성공 ·
 * markBackfillError 실패)를 직접 흉내낸다.
 *
 * <p>후보 쿼리의 실제 SQL 조건(3중 제외 규칙·나이 창·백오프)은 여기서 검증하지 않는다 — 그건
 * {@code BrandStoreTest}(Testcontainers, 실 SQL)가 정본이다. 여기 {@link InMemoryBrands}의
 * {@code findBackfillRetryCandidates}는 ACTIVE·lastSweptOn null·backfillError 有·attempts &lt;
 * maxAttempts 네 조건만 재현한다.
 */
class BrandBackfillRetryJobTest {

	private static final String RETRY_PENDING = "초기 수집에 실패했어요. 잠시 후 자동으로 다시 시도해요.";
	private static final String RETRY_EXHAUSTED = "초기 수집에 실패했어요. 다음 새벽 정기 수집에서 다시 시도해요.";

	/**
	 * retryBackfillAsync 호출을 기록하고, 그 결과(성공→touchSwept·실패→markBackfillError)를 InMemoryBrands에
	 * 직접 반영한다. {@code pendingGate}가 설정돼 있으면 완료를 미뤄 in-flight 상태를 인위적으로
	 * 유지한다(테스트 5b) — 실 backfill executor의 "재시도 1회는 수 분 걸린다"를 결정적으로 재현.
	 */
	private static final class RecordingRegistration extends BrandRegistrationService {
		final List<Long> submitted = new CopyOnWriteArrayList<>();
		volatile CompletableFuture<Void> pendingGate;
		Function<BrandRow, Boolean> succeeds = row -> true;
		private final InMemoryBrands brands;

		RecordingRegistration(InMemoryBrands brands) {
			super(null, brands, null, null, null, null, 0, Runnable::run, Runnable::run, Runnable::run, true);
			this.brands = brands;
		}

		@Override
		public CompletableFuture<Void> retryBackfillAsync(BrandRow row) {
			submitted.add(row.id());
			Runnable apply = () -> {
				if (succeeds.apply(row)) {
					brands.touchSwept(row.id(), LocalDate.now());
				} else {
					brands.markBackfillError(row.id(), "재시도도 실패");
				}
			};
			CompletableFuture<Void> gate = pendingGate;
			if (gate == null) {
				apply.run();
				return CompletableFuture.completedFuture(null);
			}
			return gate.thenRun(apply);
		}
	}

	private final InMemoryBrands brands = new InMemoryBrands();
	private final RecordingRegistration registration = new RecordingRegistration(brands);

	private BrandBackfillRetryJob job(int maxAttempts) {
		return new BrandBackfillRetryJob(brands, registration, maxAttempts,
				Duration.ofHours(6), Duration.ofMinutes(5), 5);
	}

	private void activeRow(long id, String username, int attempts) {
		brands.rows.put(username, new BrandRow(id, username, "ig" + id, BrandStatus.ACTIVE, null, 12, true));
		brands.backfillErrors.put(id, RETRY_PENDING);
		brands.backfillAttempts.put(id, attempts);
	}

	/**
	 * (3) 1회차 sweepCore 예외로 backfill_error가 이미 기록된 브랜드 — 틱이 제출 직전
	 * markBackfillAttempt로 attempts를 1로 올린 뒤 재시도를 제출하고, 재시도(2회차)가 성공하면
	 * touchSwept가 완주 상태(last_swept_on 有 · backfill_error null · attempts 0)로 되돌린다.
	 */
	@Test
	void 열거_실패_브랜드는_재시도_성공하면_완주_상태로_수렴한다() {
		activeRow(1, "brandx", 0);

		job(3).run();

		assertThat(registration.submitted).containsExactly(1L);   // 재시도 1회 제출
		assertThat(brands.markedAttempts).containsExactly(1L);    // 제출 직전 attempts 0→1 증가(단 1회)
		assertThat(brands.touched).containsExactly(1L);           // 재시도 성공 — touchSwept
		assertThat(brands.backfillErrors).doesNotContainKey(1L);  // 클리어(유일한 해제 지점)
		assertThat(brands.backfillAttempts.get(1L)).isZero();     // touchSwept가 0으로 되돌림
		assertThat(brands.rows.get("brandx").lastSweptOn()).isNotNull();
	}

	/** (4) attempts == maxAttempts인 브랜드는 후보에서 제외돼 제출 0, 문구는 1회만 교체(재틱 재교체 없음). */
	@Test
	void 상한_도달_브랜드는_제출되지_않고_문구는_한_번만_교체된다() {
		activeRow(1, "brandx", 3);   // attempts == maxAttempts(3)

		job(3).run();
		String afterFirstTick = brands.backfillErrors.get(1L);
		job(3).run();   // 재틱 — 이미 교체된 문구는 다시 세지 않는다(멱등)

		assertThat(registration.submitted).isEmpty();           // 후보에 안 잡혀 제출 0
		assertThat(brands.markedAttempts).isEmpty();             // attempts도 안 건드림
		assertThat(afterFirstTick).isEqualTo(RETRY_EXHAUSTED);
		assertThat(brands.backfillErrors.get(1L)).isEqualTo(RETRY_EXHAUSTED);   // 재틱에도 그대로
	}

	/** (5b) 같은 브랜드의 재시도가 아직 완료 전(in-flight)이면 다음 틱은 다시 제출하지 않는다. */
	@Test
	void 진행중인_브랜드는_다음_틱에서_다시_제출되지_않는다() {
		activeRow(1, "brandx", 0);
		registration.pendingGate = new CompletableFuture<>();   // 완료를 미뤄 in-flight를 인위적으로 유지
		BrandBackfillRetryJob job = job(3);   // in-flight 셋은 잡 인스턴스 상태라 같은 인스턴스로 두 번 틱한다

		job.run();   // 1차 제출 — 게이트가 잠겨 아직 완료 안 됨
		job.run();   // 2차 틱 — 같은 브랜드가 in-flight라 스킵

		assertThat(registration.submitted).containsExactly(1L);   // 제출은 1회로 유지

		registration.pendingGate.complete(null);   // 게이트 해제 — 완료 처리(정리용, 별도 단언 없음)
	}
}
